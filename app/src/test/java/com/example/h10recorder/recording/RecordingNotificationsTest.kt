package com.example.h10recorder.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What RecordingService shows the user. The service itself needs Android; its wording doesn't. */
class RecordingNotificationsTest {
    private val config = SessionConfig("P001", "01", "B5A3F42C", "0.1.0", "8.5.0")

    @Test fun `ongoing text follows the recorder status`() {
        assertEquals("sub-P001 ses-01 · connecting to H10…", RecordingNotifications.ongoingText(RecorderStatus.Connecting, null, config))
        assertEquals("sub-P001 ses-01 · 72 bpm", RecordingNotifications.ongoingText(RecorderStatus.Recording, 72, config))
        assertEquals("sub-P001 ses-01 · waiting for heart rate…", RecordingNotifications.ongoingText(RecorderStatus.Recording, null, config))
        assertEquals("sub-P001 ses-01 · connection lost, reconnecting…", RecordingNotifications.ongoingText(RecorderStatus.Reconnecting, 72, config))
    }

    @Test fun `no alert when the user pressed stop`() {
        assertNull(RecordingNotifications.alertText(RecorderStatus.Stopped(StopReason.USER)))
        assertNull(RecordingNotifications.alertText(RecorderStatus.Recording))
    }

    @Test fun `alert when the connection could not be restored`() {
        val text = assertNotNull(RecordingNotifications.alertText(RecorderStatus.Stopped(StopReason.CONNECTION_LOST)))
        assertTrue("20s" in text, text)
        assertTrue("saved" in text)
    }

    @Test fun `alert with the error message on a crash`() {
        val text = assertNotNull(RecordingNotifications.alertText(RecorderStatus.Stopped(StopReason.ERROR, "disk full")))
        assertTrue("disk full" in text)
    }
}
