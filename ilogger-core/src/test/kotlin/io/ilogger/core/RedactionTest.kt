package io.ilogger.core

import io.ilogger.annotations.DispatchMode
import io.ilogger.annotations.LogLevel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionTest {

    private class Harness(
        val redaction: RedactionPolicy?,
        val limits: EventLimits? = null,
    ) : AutoCloseable {
        val sink = RecordingSink()
        val drops = Collections.synchronizedList(ArrayList<DroppedEvent>())
        val logger = ILogger.create {
            sink(sink)
            dispatch = DispatchMode.INLINE
            this.redaction = this@Harness.redaction
            this.limits = this@Harness.limits
            onDrop = { drops += it }
        }

        override fun close() {
            logger.close()
        }
    }

    private fun policy(block: RedactionPolicy.Builder.() -> Unit): RedactionPolicy =
        RedactionPolicy.build(block)

    @Test
    fun `key based rules mask metadata values by key`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.secretKeys())
        }).use { harness ->
            harness.logger.record(
                action = "user.login",
                metadata = mapOf(
                    "password" to "hunter2",
                    "api_key" to "sk-123",
                    "userName" to "ada",
                ),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("***", metadata["password"])
            assertEquals("***", metadata["api_key"])
            assertEquals("ada", metadata["userName"])
        }
    }

    @Test
    fun `regex rules redact emails and phones inside strings`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.email())
            rule(RedactionRules.phone())
        }).use { harness ->
            harness.logger.record(
                action = "support.ticket",
                metadata = mapOf(
                    "note" to "contact ada@example.com or +1 (555) 123-4567 today",
                    "count" to 41,
                ),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("contact *** or *** today", metadata["note"])
            assertEquals(41, metadata["count"])
        }
    }

    @Test
    fun `card rule replaces only Luhn-valid numbers`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.card())
        }).use { harness ->
            harness.logger.record(
                action = "payment.captured",
                metadata = mapOf(
                    "card" to "paying with 4111 1111 1111 1111 tonight",
                    "reference" to "order 4111111111111112 processed",
                ),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("paying with *** tonight", metadata["card"])
            assertEquals("order 4111111111111112 processed", metadata["reference"])
        }
    }

    @Test
    fun `bearer rule keeps the scheme`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.bearer())
        }).use { harness ->
            harness.logger.record(
                action = "api.probed",
                metadata = mapOf(
                    "header" to "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123",
                    "plain" to "eyJhbGciOiJIUzI1NiJ9",
                ),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("Bearer ***", metadata["header"])
            assertEquals("eyJhbGciOiJIUzI1NiJ9", metadata["plain"])
        }
    }

    @Test
    fun `actor attributes are sanitized and identity is preserved`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.email())
            rule(RedactionRules.secretKeys())
        }).use { harness ->
            harness.logger.record(
                action = "user.updated",
                actor = Actor.user(
                    "u1",
                    name = "Ada",
                    attributes = mapOf(
                        "email" to "ada@example.com",
                        "session_token" to "t-123",
                        "team" to "core",
                    ),
                ),
            )

            val actor = harness.sink.events.single().actor!!
            assertEquals("u1", actor.id)
            assertEquals(ActorType.USER, actor.type)
            assertEquals("Ada", actor.name)
            assertEquals("***", actor.attributes["email"])
            assertEquals("***", actor.attributes["session_token"])
            assertEquals("core", actor.attributes["team"])
        }
    }

    @Test
    fun `change values are redacted under their field name`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.email())
            rule(RedactionRules.keys("password"))
        }).use { harness ->
            harness.logger.record(
                action = "user.updated",
                changes = listOf(
                    FieldChange("email", "old@example.com", "new@example.com"),
                    FieldChange("password", "old-hunter2", "new-hunter2"),
                    FieldChange("title", "old", "new"),
                ),
            )

            val changes = harness.sink.events.single().changes
            assertEquals("***", changes[0].before)
            assertEquals("***", changes[0].after)
            assertEquals("***", changes[1].before)
            assertEquals("***", changes[1].after)
            assertEquals("old", changes[2].before)
            assertEquals("new", changes[2].after)
        }
    }

    @Test
    fun `nested containers are redacted with the inherited key context`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.secretKeys())
            rule(RedactionRules.email())
        }).use { harness ->
            harness.logger.record(
                action = "job.enqueued",
                metadata = mapOf(
                    "credentials" to mapOf("user" to "svc", "secret" to "s3cr3t"),
                    "contacts" to listOf("ada@example.com", "grace@example.com"),
                ),
            )

            val metadata = harness.sink.events.single().metadata
            val credentials = metadata["credentials"] as Map<*, *>
            // Key rules see the full key path: 'credentials.user' contains
            // 'credentials', so every value in the subtree is masked.
            assertEquals("***", credentials["user"])
            assertEquals("***", credentials["secret"])
            assertEquals(listOf("***", "***"), metadata["contacts"])
        }
    }

    @Test
    fun `per-category rules apply only to their category`() = runBlocking {
        Harness(policy {
            rule(RedactionRules.email())
            rule("payments", RedactionRules.card())
        }).use { harness ->
            harness.logger.record(
                action = "payment.captured",
                category = "payments",
                metadata = mapOf("detail" to "card 4111111111111111 by a@b.co"),
            )
            harness.logger.record(
                action = "task.created",
                category = "workflows",
                metadata = mapOf("detail" to "card 4111111111111111 by a@b.co"),
            )

            val events = harness.sink.events
            // payments: default email rule + category card rule.
            assertEquals("card *** by ***", events[0].metadata["detail"])
            // workflows: default email rule only — the card number survives.
            assertEquals("card 4111111111111111 by ***", events[1].metadata["detail"])
        }
    }

    @Test
    fun `strict policy rejects events when a rule throws`() = runBlocking {
        val throwing = object : RedactionRule {
            override val name = "broken"
            override fun redact(key: String, value: String): String = throw IllegalStateException("boom")
        }
        Harness(policy {
            strict = true
            rule(throwing)
        }).use { harness ->
            harness.logger.record(action = "job.ran", metadata = mapOf("k" to "v"))

            assertEquals(0, harness.sink.count())
            val drop = harness.drops.single()
            assertEquals(DropReason.STRICT_REJECT, drop.reason)
            assertNull(drop.event)
            assertTrue(drop.error!!.message!!.startsWith("ilogger redaction:"))
            assertTrue(drop.error.message!!.contains("rule 'broken' failed"))
        }
    }

    @Test
    fun `lenient policy skips failing rules and keeps working rules`() = runBlocking {
        val throwing = object : RedactionRule {
            override val name = "broken"
            override fun redact(key: String, value: String): String = throw IllegalStateException("boom")
        }
        Harness(policy {
            rule(throwing)
            rule(RedactionRules.email())
        }).use { harness ->
            harness.logger.record(
                action = "job.ran",
                metadata = mapOf("k" to "ada@example.com"),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("***", metadata["k"])
            assertEquals(0, harness.drops.size)
        }
    }

    @Test
    fun `redaction runs before limits so masks count toward the budget`() = runBlocking {
        Harness(
            redaction = policy { rule(RedactionRules.email()) },
            limits = EventLimits(maxStringLength = 20, action = LimitAction.TRUNCATE),
        ).use { harness ->
            // The email is 26 characters — over the limit. Redaction first
            // collapses it to the mask, so nothing ever truncates.
            harness.logger.record(
                action = "user.contact",
                metadata = mapOf("note" to "xxxxxxxxxxxxxx@example.com"),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("***", metadata["note"])
            assertEquals(0, harness.logger.stats().truncated)
        }
    }

    @Test
    fun `log safe values are left untouched`() = runBlocking {
        Harness(policy { rule(RedactionRules.email()) }).use { harness ->
            harness.logger.record(
                action = "export.finished",
                metadata = mapOf(
                    "report" to LogSafeValue.of("owner ada@example.com"),
                    "note" to "ada@example.com",
                ),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("owner ada@example.com", (metadata["report"] as LogSafeValue).value)
            assertEquals("***", metadata["note"])
        }
    }

    @Test
    fun `without a policy nothing is redacted`() = runBlocking {
        Harness(null).use { harness ->
            harness.logger.record(
                action = "user.login",
                metadata = mapOf("password" to "hunter2", "note" to "ada@example.com"),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("hunter2", metadata["password"])
            assertEquals("ada@example.com", metadata["note"])
        }
    }

    @Test
    fun `shared policy is safe under concurrent recording`() = runBlocking {
        Harness(policy { rule(RedactionRules.email()) }).use { harness ->
            val threads = 8
            val perThread = 25
            val ready = CountDownLatch(threads)
            val done = CountDownLatch(threads)
            repeat(threads) { index ->
                Thread {
                    ready.countDown()
                    ready.await()
                    repeat(perThread) { n ->
                        harness.logger.recordNow(
                            action = "user.login",
                            metadata = mapOf("note" to "user-$index-$n@example.com"),
                        )
                    }
                    done.countDown()
                }.start()
            }
            done.await()

            // recordNow defaults to ASYNC dispatch: delivery goes through the
            // engine worker, so wait for it instead of asserting immediately.
            val expected = threads * perThread
            val deadline = System.currentTimeMillis() + 5_000
            while (harness.sink.count() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(5)
            }

            assertEquals(expected, harness.sink.count())
            assertEquals(0, harness.drops.size)
            for (event in harness.sink.events) {
                assertEquals("***", event.metadata["note"])
            }
        }
    }

    @Test
    fun `audit level still records with redaction applied`() = runBlocking {
        Harness(policy { rule(RedactionRules.secretKeys()) }).use { harness ->
            harness.logger.record(
                action = "user.login",
                level = LogLevel.AUDIT,
                metadata = mapOf("password" to "hunter2"),
            )

            val metadata = harness.sink.events.single().metadata
            assertEquals("***", metadata["password"])
        }
    }
}
