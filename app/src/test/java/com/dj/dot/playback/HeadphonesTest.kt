package com.dj.dot.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadphonesTest {
    private val buds = Headphones.Info("Buds", 90, "AA:BB:CC:DD:EE:01")

    @Test fun `battery from the earphones themselves is accepted`() {
        assertTrue(buds.isDevice("AA:BB:CC:DD:EE:01"))
    }

    @Test fun `battery from a watch or tracker is ignored`() {
        assertFalse(buds.isDevice("AA:BB:CC:DD:EE:02"))
    }

    @Test fun `a broadcast that doesn't say who sent it is ignored`() {
        assertFalse(buds.isDevice(null))
    }

    @Test fun `earphones without a known address accept nothing`() {
        assertFalse(Headphones.Info("Buds", 90).isDevice("AA:BB:CC:DD:EE:01"))
        assertFalse(Headphones.Info("Buds", 90).isDevice(null))
    }

    @Test fun `pickActiveDevice returns null when no devices connected`() {
        assertNull(Headphones.pickActiveDevice(emptyList(), setOf("AA:BB:CC:DD:EE:01")))
    }

    @Test fun `pickActiveDevice returns first device when active outputs empty`() {
        assertEquals("AA:BB:CC:DD:EE:01", Headphones.pickActiveDevice(listOf("AA:BB:CC:DD:EE:01"), emptySet()))
    }

    @Test fun `pickActiveDevice selects active output when multiple devices connected`() {
        val car = "AA:BB:CC:DD:EE:01"
        val earbuds = "AA:BB:CC:DD:EE:02"
        val connected = listOf(car, earbuds)
        val activeOutputs = setOf(earbuds)
        assertEquals(earbuds, Headphones.pickActiveDevice(connected, activeOutputs))
    }

    @Test fun `pickActiveDevice falls back to first device when active output does not match`() {
        val car = "AA:BB:CC:DD:EE:01"
        val earbuds = "AA:BB:CC:DD:EE:02"
        val speaker = "AA:BB:CC:DD:EE:99"
        val connected = listOf(car, earbuds)
        val activeOutputs = setOf(speaker)
        assertEquals(car, Headphones.pickActiveDevice(connected, activeOutputs))
    }
}
