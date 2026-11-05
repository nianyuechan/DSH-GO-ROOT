package io.github.ccch1mneyyy.dsh_app.phone.a11y

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewMappingTest {
    private val geometry = ImageGeometry(
        originX = 100, originY = 200,
        scaleX = 0.5f, scaleY = 0.5f,
        width = 500, height = 1_000,
    )

    @Test fun fitCenterMapsScreenCoordinatesThroughImagePixelsAndLetterboxing() {
        // The 500x1000 image fits a 250x600 view at 0.5: 250x500, centred with 50 px above.
        val point = PreviewMapping.toView(600, 1_200, geometry, 250, 600)!!
        assertEquals(125f, point.x, 0.01f)
        assertEquals(300f, point.y, 0.01f)
        val origin = PreviewMapping.toView(100, 200, geometry, 250, 600)!!
        assertEquals(0f, origin.x, 0.01f)
        assertEquals(50f, origin.y, 0.01f)
    }

    @Test fun aBackgroundFrameUsesTheSameFitCenterMappingAsTheMainScreen() {
        val background = ImageGeometry(0, 0, 1f, 1f, 720, 1_280)
        val point = PreviewMapping.toView(360, 640, background, 180, 400)!!

        assertEquals(90f, point.x, 0.01f)
        assertEquals(200f, point.y, 0.01f)
        assertEquals(PreviewPoint(0f, 40f), PreviewMapping.toView(0, 0, background, 180, 400))
    }

    @Test fun pointsOutsideTheCapturedImageOrAnEmptyViewHaveNoFeedbackLocation() {
        assertNull(PreviewMapping.toView(99, 200, geometry, 250, 600))
        assertNull(PreviewMapping.toView(100, 2_200, geometry, 250, 600))
        assertNull(PreviewMapping.toView(600, 1_200, geometry, 0, 600))
    }
}
