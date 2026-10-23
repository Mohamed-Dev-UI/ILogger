package io.ilogger.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs the tamper-evident chain input of one audit event and returns the
 * stamp stored as the event's `ilogger.trail.hash` metadata value.
 *
 * Implementations must be deterministic: signing the same input with the same
 * key always produces the same output (true for HMAC-SHA256 and for Ed25519,
 * which RFC 8032 defines as deterministic). [ilogger-core] ships
 * [HmacTrailSigner]; hosts on JDK 15+ (Android API 33+) can wrap
 * `java.security.Signature("Ed25519")` — hash the input with SHA-256 first,
 * then sign the digest.
 */
public interface TrailSigner {

    /** Returns the stamp for [chainInput]; output size is implementation-defined. */
    public fun sign(chainInput: ByteArray): ByteArray

    /** Constant-time equality helper for verifying a stored stamp. */
    public fun verify(chainInput: ByteArray, stamp: ByteArray): Boolean =
        MessageDigest.isEqual(sign(chainInput), stamp)
}

/**
 * HMAC-SHA256 stamps ([javax.crypto.Mac]). The key SHOULD come from a secret
 * store, never be committed, and be rotated by issuing a new
 * [SigningKey] id — verifiers keep old keys until every record stamped with
 * them has aged out.
 */
public class HmacTrailSigner(key: ByteArray) : TrailSigner {

    private val keySpec = SecretKeySpec(key, "HmacSHA256")

    // One Mac per thread: doFinal() resets it, so instances are reusable, and
    // per-call Mac.getInstance() would dominate the stamping cost.
    private val macs = object : ThreadLocal<Mac>() {
        override fun initialValue(): Mac = Mac.getInstance("HmacSHA256").apply { init(keySpec) }
    }

    override fun sign(chainInput: ByteArray): ByteArray = macs.get().doFinal(chainInput)
}

/** One key of the audit trail: a stable [id] plus its [signer]. */
public class SigningKey(public val id: String, public val signer: TrailSigner)

/**
 * Decorator [LogSink] that turns the stream it forwards into a tamper-evident
 * chain. Every event leaving this sink carries four metadata stamps:
 *
 * - `ilogger.trail.seq` — position in the chain, starting at 0 per instance;
 * - `ilogger.trail.prev` — previous stamp, [GENESIS_STAMP] for the first event;
 * - `ilogger.trail.hash` — `signer.sign(prevStampBytes || canonical(event))`;
 * - `ilogger.trail.keyId` — the [SigningKey.id] used for this stamp.
 *
 * Rotation: pass a `() -> SigningKey` provider backed by your key store and
 * swap the reference it returns — the chain continues seamlessly, records
 * stamped before the switch keep the old key id, and verifiers hold both
 * keys until the old records age out.
 *
 * Chain update and the downstream write are serialized under one mutex, so
 * the chain order is exactly the write order the wrapped sink observes. The
 * canonical form (see [TrailVerifier]) excludes `ilogger.*` metadata keys,
 * so stamps never feed back into the hash.
 *
 * Threat model: an HMAC/Ed25519 chain detects **modification** of any record,
 * **reordering** and **deletion inside a segment**, and **insertion** (the
 * attacker cannot forge the next stamp without the key). It does *not* detect
 * deleting a whole trailing segment — anchor the last stamp externally (e.g.
 * publish it to a second system) when that matters. A process restart starts
 * a new segment (seq 0 with the genesis stamp); [TrailVerifier] accepts
 * legitimate segment boundaries.
 */
public class HashChainSink(
    private val delegate: LogSink,
    private val keyProvider: () -> SigningKey,
) : LogSink {

    /** Convenience constructor for deployments that never rotate keys. */
    public constructor(delegate: LogSink, key: SigningKey) : this(delegate, { key })

    private val mutex = Mutex()
    private var seq: Long = 0
    private var prevStamp: ByteArray = GENESIS_BYTES

    override suspend fun write(event: LogEvent) {
        mutex.withLock {
            val key = keyProvider()
            val input = TrailVerifier.chainInput(event, seq, prevStamp)
            val stamp = key.signer.sign(input)
            val stamped = event.copy(
                metadata = buildMap(event.metadata.size + 4) {
                    putAll(event.metadata)
                    put(TrailKeys.SEQ, seq)
                    put(TrailKeys.PREV, encode(prevStamp))
                    put(TrailKeys.HASH, encode(stamp))
                    put(TrailKeys.KEY_ID, key.id)
                },
            )
            seq += 1
            prevStamp = stamp
            // Written under the same lock, so the wrapped sink observes
            // exactly the chain order.
            delegate.write(stamped)
        }
    }

    override suspend fun writeAll(events: List<LogEvent>) {
        for (event in events) write(event)
    }

    override suspend fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }

    public companion object {
        /** Chain seed: 32 zero bytes, rendered as all-`A` base64url. */
        public val GENESIS_BYTES: ByteArray = ByteArray(32)

        /** [GENESIS_BYTES] in base64url — the `ilogger.trail.prev` of a segment start. */
        public val GENESIS_STAMP: String = encode(GENESIS_BYTES)

        internal fun encode(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

/** Reserved metadata keys written by [HashChainSink]. */
public object TrailKeys {
    public const val SEQ: String = "ilogger.trail.seq"
    public const val PREV: String = "ilogger.trail.prev"
    public const val HASH: String = "ilogger.trail.hash"
    public const val KEY_ID: String = "ilogger.trail.keyId"

    /** Prefix excluded from the canonical form (stamps never feed the hash). */
    public const val RESERVED_PREFIX: String = "ilogger."
}

/**
 * Result of [TrailVerifier.verify].
 */
public class TrailVerification(
    /** `true` when every record checked out. */
    public val valid: Boolean,
    /** Number of records examined before the verdict (whole list when valid). */
    public val checked: Int,
    /** 0-based index of the first broken record, or `null` when [valid]. */
    public val brokenAt: Int?,
    /** Human-readable description of the first failure, or `null` when [valid]. */
    public val reason: String?,
)

/**
 * Verifies a hash-chained sequence of events (a JSONL file parsed back, a
 * Room export, or the in-memory events a test sink captured).
 *
 * With [keys], every stamp is recomputed from the record content and compared
 * — this is what makes modification, reordering and insertion detectable.
 * With no keys, only the chain *structure* is checked: stamps present,
 * sequence numbers consistent, links connecting (a stamp cannot be recomputed
 * without the key, so tampering with record content is not detectable in
 * structural mode).
 */
public object TrailVerifier {

    public fun verify(
        events: List<LogEvent>,
        keys: Map<String, TrailSigner> = emptyMap(),
    ): TrailVerification {
        var expectedSeq = 0L
        var prevStamp: ByteArray? = null
        for ((index, event) in events.withIndex()) {
            val failure = verifyOne(event, expectedSeq, prevStamp, keys)
            if (failure != null) {
                return TrailVerification(false, index + 1, index, failure)
            }
            expectedSeq = (event.metadata.longAt(TrailKeys.SEQ) ?: expectedSeq) + 1
            prevStamp = event.metadata.stampAt(TrailKeys.HASH)
        }
        return TrailVerification(true, events.size, null, null)
    }

    private fun verifyOne(
        event: LogEvent,
        expectedSeq: Long,
        prevStamp: ByteArray?,
        keys: Map<String, TrailSigner>,
    ): String? {
        val metadata = event.metadata
        val seq = metadata.longAt(TrailKeys.SEQ) ?: return "missing ${TrailKeys.SEQ}"
        val prev = metadata.stampAt(TrailKeys.PREV) ?: return "missing ${TrailKeys.PREV}"
        val stamp = metadata.stampAt(TrailKeys.HASH) ?: return "missing ${TrailKeys.HASH}"
        val keyId = metadata.stringAt(TrailKeys.KEY_ID) ?: return "missing ${TrailKeys.KEY_ID}"

        val segmentStart = seq == 0L && prev.contentEquals(HashChainSink.GENESIS_BYTES)
        if (!segmentStart) {
            if (seq != expectedSeq) return "sequence $seq does not follow ${expectedSeq - 1}"
            val expectedPrev = prevStamp
                ?: return "record continues a chain whose start is not in the verified list"
            if (!prev.contentEquals(expectedPrev)) {
                return "${TrailKeys.PREV} does not link to the previous record"
            }
        }
        // A segment start (seq 0 chained to the genesis stamp) is accepted
        // anywhere in the list: a store written across process restarts
        // legitimately contains one chain per process.

        if (keys.isEmpty()) return null
        val signer = keys[keyId] ?: return "no key for id '$keyId'"
        val input = chainInput(event, seq, prev)
        if (!signer.verify(input, stamp)) {
            return "stamp does not match record content (modified, reordered or forged)"
        }
        return null
    }

    /**
     * Canonical chain input for [event] at position [seq], chained to
     * [prevStamp]. Length-prefixed fields, metadata keys sorted, `ilogger.*`
     * keys excluded, values rendered with the same deterministic degradation
     * the JSON codec uses (scalars directly, anything else via `toString()`).
     */
    internal fun chainInput(event: LogEvent, seq: Long, prevStamp: ByteArray): ByteArray {
        val sb = StringBuilder(256)
        fun field(value: String) {
            sb.append(value.length).append(':').append(value).append('|')
        }
        field("v1")
        field(event.id)
        field(event.timestampMillis.toString())
        field(event.level.name)
        field(event.actionVersion.toString())
        field(event.action)
        field(event.outcome.name)
        field(event.actor?.type?.name ?: "-")
        field(event.actor?.id ?: "-")
        field(event.actor?.name ?: "-")
        field(event.entity ?: "-")
        field(event.entityId ?: "-")
        field(event.source ?: "-")
        field(event.category ?: "-")
        field(event.correlationId ?: "-")
        field(event.causationId ?: "-")
        field(event.failure?.errorType ?: "-")
        field(event.failure?.message ?: "-")
        field(seq.toString())
        field(HashChainSink.encode(prevStamp))

        val metadata = event.metadata.entries
            .filter { !it.key.startsWith(TrailKeys.RESERVED_PREFIX) }
            .sortedBy { it.key }
        for ((key, value) in metadata) {
            field(key)
            field(canonicalValue(value, 0))
        }
        for (change in event.changes) {
            field(change.field)
            field(canonicalValue(change.before, 0))
            field(canonicalValue(change.after, 0))
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // Container depth bound and marker mirror JsonEventCodec's structure depth
    // ("2", "<max-depth>"), so a stamp computed on the original event verifies
    // identically after a JSONL encode/decode round trip. Keep them in sync —
    // ilogger-serialization depends on ilogger-core, not the other way round.
    private const val CANONICAL_MAX_DEPTH = 2
    private const val DEPTH_MARKER = "<max-depth>"

    /**
     * Deterministic rendering of a metadata/change value for the chain input.
     * Containers render structurally up to [CANONICAL_MAX_DEPTH] (deeper:
     * [DEPTH_MARKER], never `toString()`, which would recurse through cyclic
     * collections); scalars render as their stable string form.
     */
    private fun canonicalValue(value: Any?, depth: Int): String = when {
        value == null -> "\u0000"
        value is String -> value
        value is Number || value is Boolean || value is Char -> value.toString()
        value is java.util.UUID || value is java.time.Instant || value is java.time.Duration -> value.toString()
        value is Enum<*> -> value.name
        value is Map<*, *> ->
            if (depth >= CANONICAL_MAX_DEPTH) DEPTH_MARKER
            else value.entries.joinToString(prefix = "{", postfix = "}") { (k, v) ->
                "$k=${canonicalValue(v, depth + 1)}"
            }
        value is Iterable<*> ->
            if (depth >= CANONICAL_MAX_DEPTH) DEPTH_MARKER
            else value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it, depth + 1) }
        value is Array<*> ->
            if (depth >= CANONICAL_MAX_DEPTH) DEPTH_MARKER
            else value.joinToString(prefix = "[", postfix = "]") { canonicalValue(it, depth + 1) }
        value is IntArray -> value.joinToString(prefix = "[", postfix = "]")
        value is LongArray -> value.joinToString(prefix = "[", postfix = "]")
        value is ShortArray -> value.joinToString(prefix = "[", postfix = "]")
        value is ByteArray -> value.joinToString(prefix = "[", postfix = "]")
        value is DoubleArray -> value.joinToString(prefix = "[", postfix = "]")
        value is FloatArray -> value.joinToString(prefix = "[", postfix = "]")
        value is BooleanArray -> value.joinToString(prefix = "[", postfix = "]")
        value is CharArray -> value.joinToString(prefix = "[", postfix = "]")
        value is UIntArray -> value.joinToString(prefix = "[", postfix = "]")
        value is ULongArray -> value.joinToString(prefix = "[", postfix = "]")
        value is UShortArray -> value.joinToString(prefix = "[", postfix = "]")
        value is UByteArray -> value.joinToString(prefix = "[", postfix = "]")
        else -> try {
            value.toString()
        } catch (_: Throwable) {
            value::class.java.name
        }
    }

    private fun Map<String, Any?>.longAt(key: String): Long? =
        (this[key] as? Number)?.toLong()

    private fun Map<String, Any?>.stringAt(key: String): String? =
        this[key] as? String

    private fun Map<String, Any?>.stampAt(key: String): ByteArray? =
        (this[key] as? String)?.let {
            try {
                Base64.getUrlDecoder().decode(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}
