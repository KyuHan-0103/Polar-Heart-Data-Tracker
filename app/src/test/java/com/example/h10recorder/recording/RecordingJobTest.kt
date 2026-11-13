package com.example.h10recorder.recording

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

/** superviseRecording = RecordingService's job. finish (release wake lock, stopSelf) must always run exactly once. */
class RecordingJobTest {
    private fun runJob(record: suspend () -> Unit, cancel: Boolean) = runTest {
        val order = mutableListOf<String>()
        val job = launch {
            superviseRecording(
                record = record,
                notify = { try { awaitCancellation() } finally { order += "notifier stopped" } },
                finish = { order += "finish" },
            )
        }
        runCurrent()
        if (cancel) job.cancel()
        job.join()
        assertEquals(listOf("notifier stopped", "finish"), order)
    }

    @Test fun `finish runs after Stop cancels the job`() = runJob(record = { awaitCancellation() }, cancel = true)
    @Test fun `finish runs after the recording ends on its own`() = runJob(record = { yield() }, cancel = false)   // yield: let the notifier start
    @Test fun `finish runs after the recording fails`() = runJob(record = { yield(); error("boom") }, cancel = false)
}
