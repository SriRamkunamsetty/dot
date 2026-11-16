package com.dj.dot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun appStateSavesAndRestoresStackAndFlags() {
        val original = AppState().apply {
            push(Screen.Search)
            push(Screen.Album(42L))
            push(Screen.Artist("Artist Name"))
            push(Screen.Folder("/storage/Music"))
            push(Screen.Playlist("favs"))
            nowPlaying = true
            party = true
            bedside = true
        }

        val saved = with(AppStateSaver) {
            val scope = androidx.compose.runtime.saveable.SaverScope { true }
            scope.save(original)
        }

        val restored = AppStateSaver.restore(saved!!)!!

        assertEquals(6, restored.stack.size)
        assertEquals(Screen.Home, restored.stack[0])
        assertEquals(Screen.Search, restored.stack[1])
        assertEquals(Screen.Album(42L), restored.stack[2])
        assertEquals(Screen.Artist("Artist Name"), restored.stack[3])
        assertEquals(Screen.Folder("/storage/Music"), restored.stack[4])
        assertEquals(Screen.Playlist("favs"), restored.stack[5])
        assertTrue(restored.nowPlaying)
        // party and bedside are temporary and should not be persisted across process death/uiMode recreation
        assertFalse(restored.party)
        assertFalse(restored.bedside)
    }

    @Test
    fun appStateSkipsInvalidKeysOnRestore() {
        val savedWithInvalid = listOf(
            listOf("home", "album/invalid_id", "unknown_screen", "artist/Valid"),
            false,
        )

        val restored = AppStateSaver.restore(savedWithInvalid)!!
        assertEquals(2, restored.stack.size)
        assertEquals(Screen.Home, restored.stack[0])
        assertEquals(Screen.Artist("Valid"), restored.stack[1])
        assertFalse(restored.nowPlaying)
    }
}
