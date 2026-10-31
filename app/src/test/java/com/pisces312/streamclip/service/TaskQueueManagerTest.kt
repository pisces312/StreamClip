package com.pisces312.streamclip.service

import com.pisces312.streamclip.model.BatchTaskItem
import com.pisces312.streamclip.model.TaskConfig
import com.pisces312.streamclip.model.TaskStatus
import com.pisces312.streamclip.model.TaskType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Host-JVM tests for the batch queue state machine in [TaskQueueManager].
 *
 * These pin down the fixes for the broken cancel/retry chain (see docs/batch-task-fix-plan.md):
 * - cancelling a PENDING task must dequeue it, not just flip a status that `next()` overwrites;
 * - CANCELLED is terminal: late success/failure callbacks from a killed FFmpeg session
 *   must not flip it back to COMPLETED/FAILED;
 * - COMPLETED is terminal against late cancels;
 * - retryTask returns the fresh PENDING copy so the UI can (re)start BatchTaskService.
 *
 * TaskQueueManager is a singleton; clearForTesting() isolates each case.
 */
class TaskQueueManagerTest {

    @Before
    fun resetSingleton() {
        TaskQueueManager.clearForTesting()
    }

    private fun task(id: String = java.util.UUID.randomUUID().toString()): BatchTaskItem =
        BatchTaskItem(
            id = id,
            type = TaskType.COMPRESS,
            inputPath = "/tmp/in.mp4",
            outputPath = "/tmp/out-$id.mp4",
            config = TaskConfig()
        )

    // --- cancel must dequeue, not just flip status ---

    @Test
    fun markCancelled_removes_pending_task_from_queue() {
        // Regression: cancel only set status=CANCELLED; next() still returned the task
        // and overwrote the status back to RUNNING, so cancelled tasks ran anyway.
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))
        TaskQueueManager.markCancelled(t.id)

        assertFalse(TaskQueueManager.hasPending())
        assertNull(TaskQueueManager.next())
        assertEquals(TaskStatus.CANCELLED, TaskQueueManager.getTask(t.id)?.status)
    }

    @Test
    fun markCancelled_leaves_other_queued_tasks_intact() {
        val a = task()
        val b = task()
        TaskQueueManager.enqueueAll(listOf(a, b))
        TaskQueueManager.markCancelled(a.id)

        assertTrue(TaskQueueManager.hasPending())
        val next = TaskQueueManager.next()
        assertEquals(b.id, next?.id)
        assertEquals(TaskStatus.RUNNING, TaskQueueManager.getTask(b.id)?.status)
    }

    // --- terminal-state guards ---

    @Test
    fun markCompleted_cannot_resurrect_cancelled_task() {
        // Regression: cancel the UI status while FFmpeg keeps running; when the encode
        // finished, markCompleted flipped CANCELLED -> COMPLETED and the "cancelled"
        // task showed up as a success with an output file.
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))
        TaskQueueManager.next() // RUNNING
        TaskQueueManager.markCancelled(t.id)
        TaskQueueManager.markCompleted(t.id)

        assertEquals(TaskStatus.CANCELLED, TaskQueueManager.getTask(t.id)?.status)
        assertEquals(0, TaskQueueManager.getTask(t.id)?.progress)
    }

    @Test
    fun markFailed_cannot_resurrect_cancelled_task() {
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))
        TaskQueueManager.next() // RUNNING
        TaskQueueManager.markCancelled(t.id)
        TaskQueueManager.markFailed(t.id, "late error")

        assertEquals(TaskStatus.CANCELLED, TaskQueueManager.getTask(t.id)?.status)
        assertNull(TaskQueueManager.getTask(t.id)?.errorMessage)
    }

    @Test
    fun markCancelled_cannot_uncomplete_task() {
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))
        TaskQueueManager.next() // RUNNING
        TaskQueueManager.markCompleted(t.id)
        TaskQueueManager.markCancelled(t.id)

        assertEquals(TaskStatus.COMPLETED, TaskQueueManager.getTask(t.id)?.status)
    }

    @Test
    fun normal_lifecycle_is_unaffected_by_guards() {
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))

        val running = TaskQueueManager.next()
        assertEquals(t.id, running?.id)
        assertEquals(TaskStatus.RUNNING, TaskQueueManager.getTask(t.id)?.status)

        TaskQueueManager.updateProgress(t.id, 42)
        assertEquals(42, TaskQueueManager.getTask(t.id)?.progress)

        TaskQueueManager.markCompleted(t.id)
        assertEquals(TaskStatus.COMPLETED, TaskQueueManager.getTask(t.id)?.status)
        assertEquals(100, TaskQueueManager.getTask(t.id)?.progress)
    }

    // --- retry ---

    @Test
    fun retryTask_returns_fresh_pending_copy_and_keeps_history() {
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))
        TaskQueueManager.markFailed(t.id, "boom")

        val retried = TaskQueueManager.retryTask(t.id)
        assertNotNull(retried)

        retried!!
        assertEquals(TaskStatus.PENDING, retried.status)
        assertEquals(0, retried.progress)
        assertNull(retried.errorMessage)
        assertNull(retried.startedAt)
        assertNull(retried.completedAt)
        assertFalse(retried.id == t.id) // new identity, old record kept
        assertEquals(TaskStatus.FAILED, TaskQueueManager.getTask(t.id)?.status)

        assertTrue(TaskQueueManager.hasPending())
        val next = TaskQueueManager.next()
        assertEquals(retried.id, next?.id)
    }

    @Test
    fun retryTask_returns_null_for_completed_or_missing_task() {
        val t = task()
        TaskQueueManager.enqueueAll(listOf(t))
        TaskQueueManager.markCompleted(t.id)
        assertNull(TaskQueueManager.retryTask(t.id))
        assertNull(TaskQueueManager.retryTask("no-such-id"))
        assertFalse(TaskQueueManager.hasPending())
    }

    // --- clearCompleted ---

    @Test
    fun clearCompleted_only_removes_terminal_states() {
        val done = task()
        val cancelled = task()
        val failed = task()
        val running = task()
        TaskQueueManager.enqueueAll(listOf(done, cancelled, failed, running))
        TaskQueueManager.next() // takes `done` (RUNNING)
        TaskQueueManager.markCompleted(done.id)
        TaskQueueManager.markCancelled(cancelled.id)
        TaskQueueManager.markFailed(failed.id, "x")
        TaskQueueManager.next() // takes `running`

        TaskQueueManager.clearCompleted()

        val remaining = TaskQueueManager.getAllTasks().map { it.id }.toSet()
        assertEquals(setOf(failed.id, running.id), remaining)
    }
}
