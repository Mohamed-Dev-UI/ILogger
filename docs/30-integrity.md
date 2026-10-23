# 30. Integrity: tamper-evident audit trails

For audit scenarios where the log itself is evidence — regulated industries,
security incident review, contractual trails — ILogger can turn any event
stream into a **hash chain**: every record carries a cryptographic stamp that
covers its content *and* the previous record's stamp. Silently modifying,
reordering, inserting or deleting a record then breaks verification.

The feature ships in `ilogger-core` and is **off by default**: nothing is
stamped unless a `HashChainSink` is in the sink chain.

```kotlin
val signingKey = SigningKey("2026-q4", HmacTrailSigner(keyBytesFromYourSecretStore))

ILogger.create {
    sink(HashChainSink(TextFileSink(file = auditDir.resolve("audit.jsonl")), signingKey))
    dispatch = DispatchMode.ASYNC
}
```

## What is written

`HashChainSink` decorates any `LogSink` and stamps every event with four
reserved metadata keys (prefix `ilogger.`, so they never collide with payload
metadata):

| Key | Meaning |
|---|---|
| `ilogger.trail.seq` | Position in the chain, starting at 0 **per sink instance** |
| `ilogger.trail.prev` | Previous stamp, base64url — the all-zero *genesis* stamp for the first record of a segment |
| `ilogger.trail.hash` | `signer.sign(prevStampBytes ‖ canonical(event))` |
| `ilogger.trail.keyId` | The `SigningKey.id` used for this stamp |

The canonical form is a length-prefixed rendering of every event field —
including sorted, non-reserved metadata and changes — deterministic and
bounded (containers deeper than the JSON codec's structure depth render as
`"<max-depth>"`, exactly what the JSON codec writes, so stamps survive a JSONL
round trip). Chain update and the downstream write are serialized under one
mutex: the wrapped sink observes exactly the chain order, so concurrent
producers cannot interleave stamps.

## Signing keys

`TrailSigner` has one method, `sign(chainInput): ByteArray`, plus a
constant-time `verify` default. Ships with:

- **`HmacTrailSigner(key: ByteArray)`** — HMAC-SHA256, works everywhere the
  library works. **Recommended default.**
- **Ed25519** — wrap `java.security.Signature("Ed25519")` (JDK 15+, Android
  API 33+): hash the chain input with SHA-256 first, then sign the digest.
  Choose it when *non-repudiation* matters — an HMAC only proves that whoever
  holds the symmetric key produced the record, while Ed25519 pins it to a
  private key that the verifier never sees.

Key management is deliberately out of scope: **load the key material from your
secret store, never commit it, and never derive it from constants in the
binary.** Losing the key does not make records unreadable (stamps are metadata
alongside the payload) but makes them unverifiable.

### Rotation

Pass a key provider instead of a fixed key — `HashChainSink(delegate) {
currentKey() }`. Each record carries the id of the key that stamped it, the
chain continues seamlessly across the switch, and verifiers keep old keys
until every record stamped with them has aged out (see below).

## Verification

```kotlin
val keys: Map<String, TrailSigner> = mapOf(
    "2026-q4" to HmacTrailSigner(q4Bytes),
    "2026-q1" to HmacTrailSigner(q1Bytes),
)
val result = TrailVerifier.verify(events, keys)
if (!result.valid) {
    // result.brokenAt — 0-based index of the first bad record
    // result.reason   — human-readable description
    // result.checked  — records examined before the verdict
}
```

`TrailVerifier.verify` takes the events in order — from a JSONL file parsed
with `JsonEventCodec.decode` (see [15](15-json-codec.md)), a Room export, or
an in-memory list.

Two modes:

| Mode | Call | Detects |
|---|---|---|
| **Keyed** (recommended) | `verify(events, keys)` | Modification, reordering, insertion, in-segment deletion — every stamp is recomputed from record content |
| **Structural only** | `verify(events)` | Missing stamps, sequence gaps, broken links. Stamps cannot be recomputed without keys, so content tampering is *not* detectable — use it only for quick consistency checks or when keys are unavailable |

A **segment start** (seq 0 chained to the genesis stamp) is accepted anywhere
in the list: a store written across process restarts legitimately contains one
chain per process. Records continuing a segment whose start is not in the
verified list fail with an explicit reason — verify the whole file, not a
window.

## Threat model — and what it does not protect

| Attack | Detected? | Why |
|---|---|---|
| Modify record content (payload, metadata, action…) | ✅ keyed | Recomputed stamp no longer matches |
| Reorder records | ✅ | Sequence/link check + stamp mismatch |
| Insert a forged record | ✅ | Attacker cannot compute the next stamp without the key |
| Delete a record between two kept records | ✅ | Link/sequence break |
| **Delete a trailing segment** (records after a cut point) | ❌ | The chain of the remaining prefix is intact; a chain proves "nothing between", not "nothing after" |
| Attacker with the key | ❌ | Any chain can be re-stamped — protect the key like the data |

Mitigation for trailing-segment deletion: **anchor** the latest stamp
externally — publish each record's `ilogger.trail.hash` to a second system
(SIEM, webhook, immutable object storage) on a schedule. A stored stamp for a
record that no longer exists in the primary store is proof of deletion.

## Exporting and verifying JSONL

The stamps live in `metadata`, so `JsonEventCodec` carries them through
`encode`/`decode` unchanged:

```kotlin
// write: chain-wrapped sink (above) → audit.jsonl
// verify later:
val events = File("audit.jsonl").readLines()
    .filter { it.isNotBlank() }
    .map { JsonEventCodec.decode(it) }
val verdict = TrailVerifier.verify(events, keys)
```

Room exports (see [19](19-room-integration.md)) keep the payload verbatim, so
a `RoomLogDao.exportJsonLines` dump of a chained stream verifies the same way
once its lines are decoded.

### Verifying from the command line

`ilogger-cli verify` applies the same `TrailVerifier` without writing code —
handy for CI gates, incident review and hand-offs to auditors:

```bash
ilogger verify audit.jsonl --hmac-key prod=file:/run/secrets/audit.key
ilogger verify audit.jsonl --hmac-key old=... --hmac-key new=... --json
```

Exit code 0 means every record checked out; 1 means the file is broken (or
unreadable); 2 is a usage error. `--json` reports validity, checked/total
counts, the broken record's 0-based index and physical line, and the reason.
Encrypted files are decrypted first with `--decrypt-key`. See
[36](36-cli.md) for the full command reference.

## Cost

Measured with the deterministic harness (see [25](25-performance-and-benchmark.md),
case 6): stamping adds roughly **6 µs per event** on the sink side on the
reference dev host — ~1.8 µs canonical chain input + ~3 µs HMAC-SHA256 + stamp
metadata copy. It is paid by the sink, off the producer thread under async
dispatch. The chain is O(1) per record; verification is O(n).

## Reserved keys

Applications must not write metadata keys starting with `ilogger.` — the
prefix is reserved (stamps use it; the canonical form excludes it so stamps
never feed back into the hash). See [24](24-limits-and-constraints.md).
