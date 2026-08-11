package br.etc.victor.myhealthbridge.api

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class CanonicalJsonTest {

    @Test
    fun `reduces numbers that mean the same value to one form`() {
        assertEquals(render("""{"a":1}"""), render("""{"a":1.0}"""))
        assertEquals(render("""{"a":1}"""), render("""{"a":1e0}"""))
        assertEquals(render("""{"a":100}"""), render("""{"a":1e2}"""))
        assertEquals(render("""{"a":-0.50}"""), render("""{"a":-5e-1}"""))
    }

    @Test
    fun `keeps numbers that mean different values apart`() {
        assertNotEquals(render("""{"a":1}"""), render("""{"a":1.1}"""))
    }

    @Test
    fun `orders object keys`() {
        assertEquals(render("""{"a":1,"b":2}"""), render("""{"b":2,"a":1}"""))
        assertEquals("""{"a":1,"b":2}""", render("""{"b":2,"a":1}"""))
    }

    @Test
    fun `preserves array order`() {
        assertNotEquals(render("""{"a":[1,2]}"""), render("""{"a":[2,1]}"""))
    }

    @Test
    fun `keeps an absent property distinct from an explicit null`() {
        assertNotEquals(render("""{"a":1}"""), render("""{"a":1,"b":null}"""))
        assertEquals("""{"a":1,"b":null}""", render("""{"b":null,"a":1}"""))
    }

    @Test
    fun `preserves strings without trimming, case folding or normalizing`() {
        assertNotEquals(render("""{"a":" x"}"""), render("""{"a":"x"}"""))
        assertNotEquals(render("""{"a":"X"}"""), render("""{"a":"x"}"""))
        assertEquals("""{"a":"1"}""", render("""{"a":"1"}"""))
    }

    @Test
    fun `keeps a numeric string distinct from the number`() {
        assertNotEquals(render("""{"a":"1"}"""), render("""{"a":1}"""))
    }

    @Test
    fun `renders booleans without touching them`() {
        assertEquals("""{"a":true,"b":false}""", render("""{"b":false,"a":true}"""))
    }

    @Test
    fun `hashes the rendering, not the received bytes`() {
        assertEquals(
            CanonicalJson.digest(render("""{"a": 1.0,  "b": 2}""")).toHex(),
            CanonicalJson.digest(render("""{"b":2,"a":1}""")).toHex(),
        )
    }

    private fun render(json: String) = CanonicalJson.render(Json.parseToJsonElement(json))

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
