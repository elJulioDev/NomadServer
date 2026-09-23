package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** El delta de logs es lo único que, si se rompe, pierde líneas de la consola sin avisar. */
class LogDeltaTest {

    /** La ventana conserva las últimas [size] líneas de las [total] escritas. */
    private fun windowAt(total: Long, size: Int = 5): List<String> =
        ((total - size + 1).coerceAtLeast(1)..total).map { "linea $it" }

    @Test
    fun `primer push manda el log completo`() {
        val logs = windowAt(total = 5)
        val delta = logDelta(sameServer = false, logs = logs, total = 5, lastTotal = 0)
        assertTrue(delta.reset)
        assertEquals(logs, delta.lines)
    }

    @Test
    fun `push siguiente manda solo las lineas nuevas`() {
        val delta = logDelta(sameServer = true, logs = windowAt(total = 7), total = 7, lastTotal = 5)
        assertFalse(delta.reset)
        assertEquals(listOf("linea 6", "linea 7"), delta.lines)
    }

    @Test
    fun `ventana recortada sigue mandando solo lo nuevo`() {
        // 200 líneas escritas, pero la ventana sólo conserva las últimas 5.
        val delta = logDelta(sameServer = true, logs = windowAt(total = 200), total = 200, lastTotal = 198)
        assertFalse(delta.reset)
        assertEquals(listOf("linea 199", "linea 200"), delta.lines)
    }

    @Test
    fun `hueco mayor que la ventana fuerza reset`() {
        // La app estuvo en background: 500 líneas nuevas y sólo quedan 5.
        val logs = windowAt(total = 500)
        val delta = logDelta(sameServer = true, logs = logs, total = 500, lastTotal = 0)
        assertTrue(delta.reset)
        assertEquals(logs, delta.lines)
    }

    @Test
    fun `servidor distinto fuerza reset aunque el total no cambie`() {
        val logs = windowAt(total = 3)
        val delta = logDelta(sameServer = false, logs = logs, total = 3, lastTotal = 3)
        assertTrue(delta.reset)
        assertEquals(logs, delta.lines)
    }
}
