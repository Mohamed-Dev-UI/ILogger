package com.mohamed.dev.ui.ilogger.ui

import com.mohamed.dev.ui.ilogger.logging.BenchActions
import com.mohamed.dev.ui.ilogger.logging.InMemoryNoteBook
import com.mohamed.dev.ui.ilogger.logging.Note
import com.mohamed.dev.ui.ilogger.logging.logged
import io.ilogger.core.Actor
import io.ilogger.core.FieldChange
import io.ilogger.core.Outcome
import io.ilogger.core.withActor
import io.ilogger.testing.TestKit

/**
 * Ten assertions over the real generated wrapper, using the public TestKit.
 * Runs inside the app (TestKit screen) and in the JVM unit test suite.
 */
object BenchSuite {

    private const val FIXED_CLOCK = 1_700_000_000_000L

    suspend fun run(): List<BenchCheck> {
        val results = mutableListOf<BenchCheck>()
        val kit = TestKit.create()
        val implementation = InMemoryNoteBook()
        val book = implementation.logged(kit.logger)

        try {
            val createdId = book.create(Note(title = "write the audit"))
            val created = kit.sink.last()

            results += check(
                "Action is in the event",
                created?.action == BenchActions.CREATED,
                BenchActions.CREATED,
                created?.action?.toString(),
            )
            results += check(
                "Event ids are sequential",
                created?.id == "e1",
                "e1",
                created?.id,
            )
            results += check(
                "Fixed clock pins the timestamp",
                created?.timestampMillis == FIXED_CLOCK,
                FIXED_CLOCK.toString(),
                created?.timestampMillis?.toString(),
            )
            results += check(
                "Insert result becomes the entity id",
                created?.entity == "Note" && created.entityId == createdId.toString(),
                "Note($createdId)",
                "${created?.entity}(${created?.entityId})",
            )
            results += check(
                "Selected metadata is the title",
                created?.metadata?.get("title") == "write the audit",
                "write the audit",
                created?.metadata?.get("title")?.toString(),
            )

            // Ambient actor: the wrapper reads the coroutine context.
            withActor(Actor.user("tester", name = "Tester")) {
                book.create(Note(title = "attributed"))
            }
            val attributed = kit.sink.last()
            results += check(
                "withActor attributes the event",
                attributed?.actor?.id == "tester",
                "tester",
                attributed?.actor?.id,
            )

            // Changes are recorded on SUCCESS only.
            kit.sink.clear()
            val before = Note(id = 1, title = "before")
            book.rename(before, "after")
            val renamed = kit.sink.last()
            results += check(
                "LogChange records before → after on SUCCESS",
                renamed?.changes == listOf(FieldChange("title", "before", "after")),
                "[title: before → after]",
                renamed?.changes?.toString(),
            )

            // Failure: recorded, structured and rethrown unchanged.
            kit.sink.clear()
            val thrown = runCatching { book.explode(before) }.exceptionOrNull()
            val failed = kit.sink.last()
            results += check(
                "Failure events do not swallow exceptions",
                thrown != null && thrown === implementation.lastThrown,
                "the same exception instance reaches the caller",
                if (thrown === implementation.lastThrown) "same instance" else "different instance",
            )
            results += check(
                "Failure outcome is structured",
                failed?.outcome == Outcome.FAILURE &&
                    failed.failure?.errorType?.endsWith("IllegalStateException") == true,
                "FAILURE / java.lang.IllegalStateException",
                "${failed?.outcome} / ${failed?.failure?.errorType}",
            )

            // The clock is injectable and deterministic.
            kit.clock.set(1_700_000_500_000L)
            kit.sink.clear()
            book.create(Note(title = "tick"))
            val ticked = kit.sink.last()
            results += check(
                "FixedClock advances deterministic time",
                ticked?.timestampMillis == 1_700_000_500_000L,
                "1700000500000",
                ticked?.timestampMillis?.toString(),
            )
        } catch (error: Throwable) {
            results += BenchCheck(
                name = "Suite execution",
                passed = false,
                expected = "all checks run",
                actual = error::class.java.simpleName + ": " + error.message,
            )
        } finally {
            kit.logger.close()
        }
        return results
    }

    private fun check(name: String, passed: Boolean, expected: String?, actual: String?): BenchCheck =
        BenchCheck(
            name = name,
            passed = passed,
            expected = expected ?: "null",
            actual = actual ?: "null",
        )
}
