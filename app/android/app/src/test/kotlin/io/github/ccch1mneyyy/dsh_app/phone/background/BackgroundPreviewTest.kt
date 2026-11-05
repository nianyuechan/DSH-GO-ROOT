package io.github.ccch1mneyyy.dsh_app.phone.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundPreviewTest {
    @Test fun previewRequestsAnImageWithoutRequestingOrPublishingAModelObservation() {
        val modelSnapshotCounter = 7

        val request = BackgroundPreview.request("example.app")
        val shot = BackgroundPreview.shot(
            packageName = "example.app",
            bytes = byteArrayOf(1, 2), mime = "image/jpeg",
            width = 720, height = 1_280,
            secure = false, imageOmitted = false,
        )

        assertEquals(Proto.OP_OBSERVE, request.op)
        assertEquals("example.app", request.packageName)
        assertFalse(request.detail)
        assertTrue(request.wantImage)
        assertEquals(7, modelSnapshotCounter)
        assertEquals("background:example.app", shot.sourceKey)
        assertEquals(720, shot.geometry!!.width)
        assertEquals(1_280, shot.geometry.height)
        assertTrue(shot.repeatable)
    }

    @Test fun anUnavailableBackgroundPreviewIsStaticAndExplainsThePlaceholder() {
        val shot = BackgroundPreview.shot(
            packageName = "example.app", bytes = null, mime = null,
            width = 0, height = 0, secure = false, imageOmitted = false,
        )

        assertNull(shot.bytes)
        assertNull(shot.geometry)
        assertEquals("preview unavailable", shot.error)
        assertFalse(shot.repeatable)
    }

    @Test fun sensitiveOrOmittedBackgroundFramesClearTheImageAndStayStatic() {
        for (secure in listOf(true, false)) {
            val shot = BackgroundPreview.shot(
                packageName = "example.app", bytes = byteArrayOf(1), mime = "image/jpeg",
                width = 720, height = 1_280,
                secure = secure, imageOmitted = !secure,
            )
            assertNull(shot.bytes)
            assertNull(shot.geometry)
            assertFalse(shot.repeatable)
        }
    }
}
