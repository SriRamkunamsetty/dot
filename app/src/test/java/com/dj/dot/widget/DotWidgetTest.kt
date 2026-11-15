package com.dj.dot.widget

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class DotWidgetTest {

    private class MemoryPrefs {
        val values = mutableMapOf<String, Any>()

        @Suppress("UNCHECKED_CAST")
        private fun <T> proxy(clazz: Class<T>, handler: (Any, java.lang.reflect.Method, Array<out Any>?) -> Any?): T =
            Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { proxy, method, args ->
                handler(proxy, method, args)
            } as T

        val prefs: SharedPreferences = proxy(SharedPreferences::class.java) { _, method, args ->
            when (method.name) {
                "getString" -> values[args!![0]] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected SharedPreferences call: ${method.name}")
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val updates = mutableMapOf<String, Any?>()
            return proxy(SharedPreferences.Editor::class.java) { self, method, args ->
                when (method.name) {
                    "putString" -> {
                        updates[args!![0] as String] = args[1]
                        self
                    }
                    "apply", "commit" -> {
                        updates.forEach { (k, v) ->
                            if (v == null) values.remove(k) else values[k] = v
                        }
                        if (method.name == "commit") true else Unit
                    }
                    else -> error("Unexpected editor call: ${method.name}")
                }
            }
        }

        val context: Context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
    }

    @Test
    fun getTokenGeneratesAndPersistsToken() {
        val memory = MemoryPrefs()
        val token1 = DotWidget.getToken(memory.context)
        assertNotNull(token1)
        assertTrue(token1.isNotEmpty())

        // Successive call returns same cached token
        val token2 = DotWidget.getToken(memory.context)
        assertEquals(token1, token2)
    }
}
