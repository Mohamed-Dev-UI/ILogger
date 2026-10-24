package diagopts

import io.ilogger.testing.TestKit
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class GlobalAutoChangesTest {

    @Test
    fun `module-wide auto-changes applies when the call leaves DEFAULT`() = runTest {
        val kit = TestKit.create()
        kit.logger.use {
            val repo = object : GlobalAutoChanges {
                override suspend fun rename(oldTask: Task, newTask: Task): Boolean = true
            }.logged(kit.logger)
            repo.rename(Task(1, "old"), Task(1, "new"))

            val change = kit.sink.last()!!.changes.single()
            assertEquals("title", change.field)
            assertEquals("old", change.before)
            assertEquals("new", change.after)
        }
    }
}
