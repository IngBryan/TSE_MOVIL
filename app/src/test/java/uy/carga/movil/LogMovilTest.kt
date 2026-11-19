package uy.carga.movil

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import uy.carga.movil.datos.LogMovil

class LogMovilTest {

    private val lineas = mutableListOf<Pair<LogMovil.Nivel, String>>()
    private val original = LogMovil.salida

    @Before
    fun capturar() {
        LogMovil.salida = { nivel, linea -> lineas += nivel to linea }
    }

    @After
    fun restaurar() {
        LogMovil.salida = original
    }

    @Test
    fun `escribe un JSON con los campos comunes y los propios`() {
        LogMovil.registrar(
            "http.request", LogMovil.Nivel.WARN, correlationId = "c-1", usuario = "123", responseCode = 503,
            roundtripSeconds = 1.23456, datos = mapOf("ruta" to "/api/movil/viajes", "intentos" to 2, "nada" to null),
        )

        val (nivel, linea) = lineas.single()
        val json = Json.parseToJsonElement(linea).jsonObject
        assertEquals(LogMovil.Nivel.WARN, nivel)
        assertEquals("app-movil", json["componente"]!!.jsonPrimitive.content)
        assertEquals("c-1", json["correlationId"]!!.jsonPrimitive.content)
        assertEquals("503", json["responseCode"]!!.jsonPrimitive.content)
        assertEquals("1.235", json["roundtripSeconds"]!!.jsonPrimitive.content)
        val datos = json["datos"]!!.jsonObject
        assertEquals("2", datos["intentos"]!!.jsonPrimitive.content)
        assertFalse("nada" in datos)
    }

    @Test
    fun `los campos sin valor no se escriben`() {
        LogMovil.registrar("evento.registrado")

        val json = Json.parseToJsonElement(lineas.single().second).jsonObject
        assertFalse("usuario" in json)
        assertFalse("datos" in json)
        assertTrue("timestamp" in json)
    }
}
