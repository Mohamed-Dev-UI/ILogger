@file:OptIn(ExperimentalUnsignedTypes::class)

package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictAndRedactionEdgeCasesTest {

    private class Report(
        val logger: ILogger,
        val sink: RecordingSink,
        val drops: MutableList<DroppedEvent>,
    ) : AutoCloseable {
        override fun close() {
            logger.close()
        }
    }

    private fun create(
        mode: StrictMode = StrictMode.WARN,
        redaction: RedactionPolicy? = null,
        configure: ILoggerBuilder.() -> Unit = {},
    ): Report {
        val sink = RecordingSink()
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            strictMode = mode
            this.redaction = redaction
            onDrop = { drops += it }
            configure()
        }
        return Report(logger, sink, drops)
    }

    private fun nestedMaps(levels: Int): Any {
        var value: Any = 1
        repeat(levels) { index -> value = mapOf("k$index" to value) }
        return value
    }

    private fun nestedLists(levels: Int): Any {
        var value: Any = 1
        repeat(levels) { value = listOf(value) }
        return value
    }

    private fun nestedArrays(levels: Int): Any {
        var value: Any = 1
        repeat(levels) { value = arrayOf(value) }
        return value
    }

    // ------------------------------------------------------------------
    // Strict validation
    // ------------------------------------------------------------------

    @Test
    fun `strict mode bounds actor attributes changes and entity fields`() {
        val report = create(configure = {
            strictMaxEntries = 2
            strictMaxStringLength = 4
        })
        report.use {
            it.logger.recordNow(
                action = "a",
                actor = Actor.user("u", attributes = mapOf("a" to "1", "b" to "2", "c" to "3")),
            )
            it.logger.recordNow(
                action = "a",
                changes = listOf(
                    FieldChange("a", 1, 2),
                    FieldChange("b", 1, 2),
                    FieldChange("c", 1, 2),
                ),
            )
            it.logger.recordNow(action = "a", entity = "abcde")
            it.logger.recordNow(action = "a", entityId = "abcde")
            it.logger.recordNow(action = "a", source = "abcde")
            it.logger.recordNow(action = "a", category = "abcde")

            assertEquals(0, it.sink.count())
            assertEquals(6, it.drops.size)
            val messages = it.drops.map { drop -> drop.error!!.message.orEmpty() }
            assertTrue(messages[0].contains("actor.attributes has 3 entries; the strict limit is 2"))
            assertTrue(messages[1].contains("changes has 3 entries; the strict limit is 2"))
            assertTrue(messages[2].contains("entity is 5 characters; the strict limit is 4"))
            assertTrue(messages[3].contains("entityId is 5 characters; the strict limit is 4"))
            assertTrue(messages[4].contains("source is 5 characters; the strict limit is 4"))
            assertTrue(messages[5].contains("category is 5 characters; the strict limit is 4"))
        }
    }

    @Test
    fun `strict mode rejects deep containers and blank nested keys`() {
        create().use { report ->
            report.logger.recordNow(action = "a", metadata = mapOf("m" to nestedMaps(5)))
            report.logger.recordNow(action = "a", metadata = mapOf("l" to nestedLists(5)))
            report.logger.recordNow(action = "a", metadata = mapOf("a" to nestedArrays(5)))
            report.logger.recordNow(action = "a", metadata = mapOf("m" to mapOf("   " to 1)))

            assertEquals(0, report.sink.count())
            assertEquals(4, report.drops.size)
            val messages = report.drops.map { drop -> drop.error!!.message.orEmpty() }
            assertTrue(messages[0].contains("nested deeper than 4 levels"))
            assertTrue(messages[1].contains("nested deeper than 4 levels"))
            assertTrue(messages[2].contains("nested deeper than 4 levels"))
            assertTrue(messages[3].contains("metadata[m] key must not be blank"))
        }
    }

    @Test
    fun `strict mode counts enum names against the string limit`() {
        create(configure = { strictMaxStringLength = 8 }).use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf("e" to SampleEnum.A_VERY_LONG_CONSTANT),
            )
            assertEquals(0, report.sink.count())
            assertTrue(report.drops.single().error!!.message!!.contains("(enum)"))
        }
    }

    @Test
    fun `strict mode accepts expanded scalars, arrays and explicit safe values`() {
        create().use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf(
                    "char" to 'c',
                    "big" to BigInteger.ONE,
                    "decimal" to BigDecimal.ONE,
                    "uuid" to UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                    "instant" to Instant.parse("2026-01-01T00:00:00Z"),
                    "duration" to Duration.ofHours(2),
                    "uint" to 1u,
                    "ushort" to 1u.toUShort(),
                    "ubyte" to 1u.toUByte(),
                    "safe" to LogSafeValue.of(Any()),
                    "ints" to intArrayOf(1, 2),
                    "longs" to longArrayOf(1, 2),
                    "shorts" to shortArrayOf(1, 2),
                    "bytes" to byteArrayOf(1, 2),
                    "floats" to floatArrayOf(1f, 2f),
                    "doubles" to doubleArrayOf(1.0, 2.0),
                    "booleans" to booleanArrayOf(true, false),
                    "chars" to charArrayOf('a', 'b'),
                    "uints" to uintArrayOf(1u, 2u),
                    "ulongs" to ulongArrayOf(1uL, 2uL),
                    "ushorts" to ushortArrayOf(1u.toUShort(), 2u.toUShort()),
                    "ubytes" to ubyteArrayOf(1u.toUByte(), 2u.toUByte()),
                    "nested" to mapOf("k" to listOf(1, 2)),
                ),
                dispatch = DispatchMode.INLINE,
            )
            assertEquals(1, report.sink.count())
            assertTrue(report.drops.isEmpty())
        }
    }

    @Test
    fun `strict byte estimate covers action keys and values`() {
        create(configure = { strictMaxEventBytes = 10 }).use { report ->
            report.logger.recordNow(action = "12345", metadata = mapOf("k" to "abcdef"))
            assertEquals(0, report.sink.count())
            assertTrue(
                report.drops.single().error!!.message!!.contains("strict byte estimate of 10"),
            )
        }
    }

    @Test
    fun `log safe values render their wrapped value`() {
        assertEquals("42", LogSafeValue.of(42).toString())
        assertEquals("null", LogSafeValue.of(null).toString())
        val opaque = Any()
        assertEquals(opaque.toString(), LogSafeValue.of(opaque).toString())
    }

    // ------------------------------------------------------------------
    // Redaction enforcement
    // ------------------------------------------------------------------

    @Test
    fun `a policy with no applicable rules returns payloads untouched`() {
        val policy = RedactionPolicy.build { }
        val actor = Actor.user("u", attributes = mapOf("email" to "a@b.co"))
        val metadata = mapOf("email" to "a@b.co")
        val changes = listOf(FieldChange("email", "a@b.co", "c@d.co"))

        val outcome = RedactionEnforcement.apply(policy, null, actor, metadata, changes)
        assertTrue(outcome.accepted)
        assertSame(actor, outcome.actor)
        assertSame(metadata, outcome.metadata)
        assertSame(changes, outcome.changes)
    }

    @Test
    fun `rules that match nothing keep the original containers`() {
        val policy = RedactionPolicy.build { rule(RedactionRules.email()) }
        val actor = Actor.user("u", attributes = mapOf("team" to "core"))
        val metadata = mapOf("count" to 3)
        val changes = listOf(FieldChange("title", "a", "b"))

        val outcome = RedactionEnforcement.apply(policy, null, actor, metadata, changes)
        assertSame(actor, outcome.actor)
        assertSame(metadata, outcome.metadata)
        assertSame(changes, outcome.changes)
    }

    @Test
    fun `rules run in order and later rules see earlier output`() {
        val lowercase = object : RedactionRule {
            override val name = "lowercase"
            override fun redact(key: String, value: String): String = value.lowercase()
        }
        val mask = object : RedactionRule {
            override val name = "mask-ada"
            override fun redact(key: String, value: String): String = value.replace("ada", "***")
        }
        val policy = RedactionPolicy.build {
            rule(lowercase)
            rule(mask)
        }
        create(redaction = policy).use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf("v" to "ADA"),
                dispatch = DispatchMode.INLINE,
            )
            assertEquals("***", report.sink.events.single().metadata["v"])
        }
    }

    @Test
    fun `key matching normalizes case and separators`() {
        val equality = RedactionPolicy.build { rule(RedactionRules.keys("api key")) }
        create(redaction = equality).use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf("API_KEY" to "s1", "my_api_key" to "s2"),
                dispatch = DispatchMode.INLINE,
            )
            val metadata = report.sink.events.single().metadata
            assertEquals("***", metadata["API_KEY"])
            assertEquals("s2", metadata["my_api_key"])
        }

        val contains = RedactionPolicy.build { rule(RedactionRules.secretKeys()) }
        create(redaction = contains).use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf("my_api_key" to "s2", "password_hint" to "s3"),
                dispatch = DispatchMode.INLINE,
            )
            val metadata = report.sink.events.single().metadata
            assertEquals("***", metadata["my_api_key"])
            assertEquals("***", metadata["password_hint"])
        }
    }

    @Test
    fun `arrays iterables and non-string nested keys are rebuilt and redacted`() {
        val policy = RedactionPolicy.build {
            rule(RedactionRules.secretKeys())
            rule(RedactionRules.email())
        }
        create(mode = StrictMode.OFF, redaction = policy).use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf(
                    "tokens" to arrayOf("alpha", "beta"),
                    "contacts" to arrayOf("ada@example.com", "plain"),
                    "credentials" to linkedMapOf<Any?, Any?>(1 to "s3cr3t", "team" to "core"),
                    "people" to listOf("grace@example.com"),
                ),
                dispatch = DispatchMode.INLINE,
            )
            val metadata = report.sink.events.single().metadata
            assertEquals(listOf("***", "***"), (metadata["tokens"] as Array<*>).toList())
            assertEquals(listOf("***", "plain"), (metadata["contacts"] as Array<*>).toList())
            val credentials = metadata["credentials"] as Map<*, *>
            assertEquals("***", credentials[1])
            assertEquals("***", credentials["team"])
            assertEquals(listOf("***"), metadata["people"])
        }
    }

    @Test
    fun `redaction never mutates the caller payloads`() {
        val metadata = linkedMapOf("password" to "hunter2", "note" to "ada@example.com")
        val attributes = linkedMapOf("token" to "t-123", "team" to "core")
        val actor = Actor.user("u", attributes = attributes)
        val policy = RedactionPolicy.build {
            rule(RedactionRules.secretKeys())
            rule(RedactionRules.email())
        }
        create(redaction = policy).use { report ->
            report.logger.recordNow(
                action = "a",
                actor = actor,
                metadata = metadata,
                dispatch = DispatchMode.INLINE,
            )
            val event = report.sink.events.single()
            assertEquals("***", event.metadata["password"])
            assertEquals("***", event.actor!!.attributes["token"])
        }
        assertEquals("hunter2", metadata["password"])
        assertEquals("ada@example.com", metadata["note"])
        assertEquals("t-123", attributes["token"])
        assertEquals(Actor.user("u", attributes = mapOf("token" to "t-123", "team" to "core")), actor)
    }

    @Test
    fun `custom masks are honored by built-in rules`() {
        val policy = RedactionPolicy.build { rule(RedactionRules.email(mask = "[email]")) }
        create(redaction = policy).use { report ->
            report.logger.recordNow(
                action = "a",
                metadata = mapOf("note" to "ada@example.com"),
                dispatch = DispatchMode.INLINE,
            )
            assertEquals("[email]", report.sink.events.single().metadata["note"])
        }
    }

    @Test
    fun `an unchanged actor keeps its instance`() {
        val policy = RedactionPolicy.build { rule(RedactionRules.email()) }
        val actor = Actor.user("u", attributes = mapOf("team" to "core"))
        create(redaction = policy).use { report ->
            report.logger.recordNow(action = "a", actor = actor, dispatch = DispatchMode.INLINE)
            assertSame(actor, report.sink.events.single().actor)
        }
    }

    private enum class SampleEnum {
        A_VERY_LONG_CONSTANT,
    }
}
