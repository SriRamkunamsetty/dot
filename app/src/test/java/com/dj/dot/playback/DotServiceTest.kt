package com.dj.dot.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class DotServiceTest {

    @Test
    fun computeSampleSizeDownsamplesCorrectly() {
        // 3000 px downsampled to target 512 gives 4 (3000 / 4 = 750 px; / 8 = 375 px < 512)
        assertEquals(4, computeSampleSize(3000, target = 512))

        // 1024 px gives 2 (1024 / 2 = 512 px)
        assertEquals(2, computeSampleSize(1024, target = 512))

        // 600 px gives 1 (600 / 2 = 300 px < 512)
        assertEquals(1, computeSampleSize(600, target = 512))

        // 512 px gives 1
        assertEquals(1, computeSampleSize(512, target = 512))

        // Failed or missing bounds (0 or negative) give 1
        assertEquals(1, computeSampleSize(0, target = 512))
        assertEquals(1, computeSampleSize(-1, target = 512))
    }
}
