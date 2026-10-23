package io.ilogger.core

import io.ilogger.core.LogMasking.MASK

/**
 * One runtime redaction rule. Rules are applied at event admission (after
 * limits, before sampling and sinks) to string payloads of manual events:
 * metadata values, change before/after values and actor attributes.
 *
 * [key] is the context the value was found under — a metadata key, a change
 * field name or an actor attribute name (inherited by nested containers).
 * Rules that do not apply return [value] unchanged.
 *
 * Rule implementations must be stateless and thread-safe: one policy instance
 * is shared by every recording call of its logger. A rule that throws is
 * skipped (or rejects the event, when the policy is strict — see
 * [RedactionPolicy.strict]).
 */
public interface RedactionRule {

    /** Stable identifier used in violation messages and diagnostics. */
    public val name: String

    /** Returns the redacted form of [value], or [value] unchanged. */
    public fun redact(key: String, value: String): String
}

/**
 * Replaces whole values whose key matches a sensitive name — `"password"`,
 * `"token"`, `"cookie"`, and friends. Keys are compared normalized
 * (lowercase, `-`/`_`/space removed).
 */
public class KeyRedactionRule(
    override val name: String,
    keys: Set<String>,
    private val mask: String = MASK,
    private val matchMode: KeyMatch = KeyMatch.EQUALS,
) : RedactionRule {

    /** [EQUALS] matches the normalized key exactly; [CONTAINS] matches when any configured name occurs in it. */
    public enum class KeyMatch { EQUALS, CONTAINS }

    private val normalized: Set<String> = keys.map { normalize(it) }.toSet()

    override fun redact(key: String, value: String): String =
        if (matches(key)) mask else value

    private fun matches(key: String): Boolean {
        val normalizedKey = normalize(key)
        return when (matchMode) {
            KeyMatch.EQUALS -> normalizedKey in normalized
            KeyMatch.CONTAINS -> normalized.any { it in normalizedKey }
        }
    }

    private fun normalize(key: String): String =
        key.lowercase().replace("-", "").replace("_", "").replace(" ", "")
}

/**
 * Replaces every substring matching [pattern] with [replacement]
 * (`$1`-style group references are supported by [Regex]). Keys are ignored.
 */
public class RegexRedactionRule(
    override val name: String,
    private val pattern: Regex,
    private val replacement: String,
) : RedactionRule {

    override fun redact(key: String, value: String): String =
        pattern.replace(value, replacement)
}

/**
 * Factory for the built-in rules named by the G-35 goal: email, phone,
 * payment card, bearer token and a key-based secret set. Every rule accepts
 * a custom [mask]; the default is [LogMasking.MASK].
 */
public object RedactionRules {

    public const val DEFAULT_MASK: String = MASK

    /** Normalized key names treated as secrets by [secretKeys]. */
    public val SECRET_KEYS: Set<String> = setOf(
        "password", "passwd", "secret", "token", "accesstoken", "refreshtoken",
        "apikey", "authorization", "cookie", "setcookie", "privatekey", "credentials",
    )

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    /** 8+ digit runs with common separators (spaces, dots, dashes, parentheses, leading +). */
    private val PHONE = Regex("(?<!\\w)(\\+?\\d[\\d\\s().-]{6,}\\d)(?!\\w)")

    /** 13–19 digit runs with optional separators; candidates are Luhn-verified. */
    private val CARD_CANDIDATE = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")

    /** `Bearer <token>` credentials; the scheme is preserved. */
    private val BEARER = Regex("(?i)\\b(bearer\\s+)[A-Za-z0-9._~+/=-]{8,}")

    /** Key-based rule for the built-in secret names ([SECRET_KEYS], contains match). */
    public fun secretKeys(mask: String = DEFAULT_MASK): KeyRedactionRule =
        KeyRedactionRule("secretKeys", SECRET_KEYS, mask, KeyRedactionRule.KeyMatch.CONTAINS)

    /** Key-based rule for exactly the given names (normalized equality). */
    public fun keys(vararg names: String, mask: String = DEFAULT_MASK): KeyRedactionRule =
        KeyRedactionRule("keys", names.toSet(), mask)

    /** Replaces email addresses with [mask]. */
    public fun email(mask: String = DEFAULT_MASK): RegexRedactionRule =
        RegexRedactionRule("email", EMAIL, mask)

    /** Replaces phone-number-shaped digit runs (8+ digits, common separators) with [mask]. */
    public fun phone(mask: String = DEFAULT_MASK): RegexRedactionRule =
        RegexRedactionRule("phone", PHONE, mask)

    /**
     * Replaces payment-card numbers (13–19 digits, optional separators) that
     * pass the Luhn checksum with [mask]. Candidates failing the checksum
     * (most long digit runs, e.g. order numbers) are left alone.
     */
    public fun card(mask: String = DEFAULT_MASK): RedactionRule = object : RedactionRule {
        override val name: String = "card"

        override fun redact(key: String, value: String): String =
            CARD_CANDIDATE.replace(value) { match ->
                val digits = match.value.filter { it.isDigit() }
                if (digits.length in 13..19 && passesLuhn(digits)) mask else match.value
            }

        private fun passesLuhn(digits: String): Boolean {
            var sum = 0
            var double = false
            for (i in digits.length - 1 downTo 0) {
                var d = digits[i] - '0'
                if (double) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
                double = !double
            }
            return sum % 10 == 0
        }
    }

    /** Replaces the token of `Bearer <token>` credentials, keeping the scheme. */
    public fun bearer(mask: String = DEFAULT_MASK): RedactionRule = object : RedactionRule {
        override val name: String = "bearer"

        override fun redact(key: String, value: String): String =
            BEARER.replace(value) { match -> match.groupValues[1] + mask }
    }
}

/**
 * Immutable, thread-safe redaction configuration. Built once and handed to
 * `ILoggerBuilder.redaction`; every event of the logger is filtered by the
 * default [rules] plus, when the event's category matches, the rules
 * registered for that category.
 *
 * With [strict] set, a rule that throws while redacting rejects the whole
 * event (reported via `onDrop` with `DropReason.STRICT_REJECT`); by default
 * the failing rule is skipped and the remaining rules still apply.
 */
public class RedactionPolicy private constructor(
    public val rules: List<RedactionRule>,
    public val categoryRules: Map<String, List<RedactionRule>>,
    public val strict: Boolean,
) {

    public class Builder {
        private val rules = mutableListOf<RedactionRule>()
        private val categoryRules = mutableMapOf<String, MutableList<RedactionRule>>()

        /** See [RedactionPolicy.strict]. */
        public var strict: Boolean = false

        /** Adds a rule applied to every event. */
        public fun rule(rule: RedactionRule): Builder = apply { rules.add(rule) }

        /** Adds a rule applied only to events of [category]. */
        public fun rule(category: String, rule: RedactionRule): Builder = apply {
            categoryRules.getOrPut(category) { mutableListOf() }.add(rule)
        }

        public fun build(): RedactionPolicy = RedactionPolicy(
            rules = rules.toList(),
            categoryRules = categoryRules.mapValues { it.value.toList() },
            strict = strict,
        )
    }

    public companion object {
        public fun build(block: Builder.() -> Unit): RedactionPolicy =
            Builder().apply(block).build()
    }
}

/** Applies a [RedactionPolicy] to one event's payload at admission. */
internal object RedactionEnforcement {

    private const val MAX_DEPTH = 4

    internal class Outcome(
        val accepted: Boolean,
        val violation: String? = null,
        val actor: Actor?,
        val metadata: Map<String, Any?>,
        val changes: List<FieldChange>,
    )

    fun apply(
        policy: RedactionPolicy,
        category: String?,
        actor: Actor?,
        metadata: Map<String, Any?>,
        changes: List<FieldChange>,
    ): Outcome {
        val rules = policy.rules + policy.categoryRules[category].orEmpty()
        if (rules.isEmpty()) {
            return Outcome(accepted = true, actor = actor, metadata = metadata, changes = changes)
        }
        val ctx = Context(policy, rules)
        val redactedActor = actor?.let { ctx.actor(it) }
        if (ctx.violation != null) return Outcome(false, ctx.violation, actor, metadata, changes)
        val redactedMetadata = ctx.metadata(metadata)
        if (ctx.violation != null) return Outcome(false, ctx.violation, actor, metadata, changes)
        val redactedChanges = ctx.changes(changes)
        if (ctx.violation != null) return Outcome(false, ctx.violation, actor, metadata, changes)
        return Outcome(
            accepted = true,
            actor = redactedActor ?: actor,
            metadata = redactedMetadata,
            changes = redactedChanges,
        )
    }

    private class Context(val policy: RedactionPolicy, val rules: List<RedactionRule>) {
        var violation: String? = null

        fun string(key: String, value: String): String {
            var current = value
            for (rule in rules) {
                if (violation != null) return current
                val next = try {
                    rule.redact(key, current)
                } catch (t: Throwable) {
                    if (policy.strict) {
                        violation = "rule '${rule.name}' failed on key '$key': ${t.message}"
                        return current
                    }
                    continue
                }
                if (next != current) current = next
            }
            return current
        }

        fun actor(actor: Actor): Actor? {
            if (actor.attributes.isEmpty()) return null
            var changed = false
            val attributes = LinkedHashMap<String, String>(actor.attributes.size * 2)
            for ((key, value) in actor.attributes) {
                val redacted = string(key, value)
                changed = changed || redacted !== value
                attributes[key] = redacted
            }
            return if (changed) actor.copyWith(attributes = attributes) else null
        }

        fun metadata(metadata: Map<String, Any?>): Map<String, Any?> {
            var result: LinkedHashMap<String, Any?>? = null
            for ((key, value) in metadata) {
                val redacted = redactValue(key, value, 0)
                if (redacted !== value && result == null) {
                    result = LinkedHashMap<String, Any?>(metadata.size * 2)
                    for (previous in metadata) {
                        result[previous.key] = previous.value
                        if (previous.key == key) break
                    }
                }
                result?.set(key, redacted)
            }
            return result ?: metadata
        }

        fun changes(changes: List<FieldChange>): List<FieldChange> {
            var result: MutableList<FieldChange>? = null
            for ((index, change) in changes.withIndex()) {
                val before = change.before
                val after = change.after
                val redactedBefore = if (before is String) string(change.field, before) else before
                val redactedAfter = if (after is String) string(change.field, after) else after
                if (redactedBefore !== before || redactedAfter !== after) {
                    if (result == null) {
                        result = ArrayList(changes.size)
                        for (previous in 0 until index) result.add(changes[previous])
                    }
                    result.add(FieldChange(change.field, redactedBefore, redactedAfter))
                } else {
                    result?.add(change)
                }
            }
            return result ?: changes
        }

        private fun redactValue(key: String, value: Any?, depth: Int): Any? = when {
            value is String -> string(key, value)
            value is Map<*, *> && depth < MAX_DEPTH -> {
                var changed = false
                val rebuilt = LinkedHashMap<Any?, Any?>(value.size * 2)
                for ((nestedKey, nestedValue) in value) {
                    // Key rules see the full key path, so a nested map under a
                    // sensitive key redacts all of its values.
                    val contextKey = when (nestedKey) {
                        is String -> if (key.isEmpty()) nestedKey else "$key.$nestedKey"
                        else -> key
                    }
                    val redacted = redactValue(contextKey, nestedValue, depth + 1)
                    changed = changed || redacted !== nestedValue
                    rebuilt[nestedKey] = redacted
                }
                if (changed) rebuilt else value
            }
            value is Iterable<*> && depth < MAX_DEPTH -> {
                var changed = false
                val rebuilt = ArrayList<Any?>(if (value is Collection<*>) value.size else 0)
                for (element in value) {
                    val redacted = redactValue(key, element, depth + 1)
                    changed = changed || redacted !== element
                    rebuilt.add(redacted)
                }
                if (changed) rebuilt else value
            }
            value is Array<*> && depth < MAX_DEPTH -> {
                var changed = false
                val rebuilt = arrayOfNulls<Any?>(value.size)
                for (index in value.indices) {
                    val redacted = redactValue(key, value[index], depth + 1)
                    changed = changed || redacted !== value[index]
                    rebuilt[index] = redacted
                }
                if (changed) rebuilt else value
            }
            else -> value
        }
    }
}
