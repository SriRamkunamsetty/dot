package com.dj.dot.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStateTest {
    @Test
    fun popInvokesCallbackOnlyWhenKeyNoLongerInStack() {
        val poppedScreens = mutableListOf<String>()
        val app = AppState(onScreenPopped = { poppedScreens.add(it.key) })

        // Stack: Home -> Album(1) -> Artist("A") -> Album(1)
        app.push(Screen.Album(1L))
        app.push(Screen.Artist("A"))
        app.push(Screen.Album(1L))

        assertEquals(4, app.stack.size)

        // Pop Album(1): lower Album(1) still remains in stack, so callback should NOT be invoked
        app.pop()
        assertEquals(3, app.stack.size)
        assertEquals(0, poppedScreens.size)

        // Pop Artist("A"): unique, callback should be invoked
        app.pop()
        assertEquals(2, app.stack.size)
        assertEquals(listOf("artist/A"), poppedScreens)

        // Pop second Album(1): now no other Album(1) remains, callback should be invoked
        app.pop()
        assertEquals(1, app.stack.size)
        assertEquals(listOf("artist/A", "album/1"), poppedScreens)
    }
}
