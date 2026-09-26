package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayitManagerTest {

    private val secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `resolv path is replaced in place keeping the buffer size`() {
        val original = "pre\u0000/etc/resolv.conf\u0000options\u0000post".toByteArray(Charsets.US_ASCII)
        val patched = patchResolvPath(original.copyOf())
        assertEquals(original.size, patched.size)
        // El literal original ya no está y en su hueco queda la ruta relativa (con relleno NUL
        // hasta completar los 17 bytes del hueco, que musl corta en el primer NUL).
        val text = patched.toString(Charsets.US_ASCII)
        assertTrue(text.startsWith("pre\u0000dns.conf\u0000"))
        assertTrue(text.endsWith("options\u0000post"))
        assertFalse(text.contains("/etc/resolv.conf"))
    }

    @Test
    fun `resolv path patch fails on a binary without the marker`() {
        val result = runCatching { patchResolvPath("sin marcador".toByteArray()) }
        assertTrue(result.isFailure)
    }

    @Test
    fun `secret is read from toml or as bare hex`() {
        assertEquals(secret, parseSecret("secret_key = \"$secret\"\n"))
        assertEquals(secret, parseSecret("$secret\n"))
        assertNull(parseSecret("nada útil"))
    }

    @Test
    fun `pasted secret accepts hex and trims whitespace but rejects junk`() {
        assertTrue(isValidSecret("  $secret\n"))
        assertTrue(isValidSecret("0123456789abcdef"))
        assertFalse(isValidSecret(""))
        assertFalse(isValidSecret("no-es-hex"))
        assertFalse(isValidSecret("1234"))
    }

    @Test
    fun `api errors become readable messages`() {
        assertEquals(
            "La Secret Key no es válida",
            friendly(IllegalStateException("""playit rechazó la petición: {"type":"auth","message":"InvalidAgentKey"}""")),
        )
        assertEquals(
            "Inicia sesión en playit.gg para aprobar el agente",
            friendly(IllegalStateException("""playit respondió 401: {"status":"error","data":{"type":"auth","message":"AuthRequired"}}""")),
        )
        assertEquals("algo raro", friendly(IllegalStateException("algo raro")))
    }
}
