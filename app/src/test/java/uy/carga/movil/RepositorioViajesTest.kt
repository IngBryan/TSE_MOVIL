package uy.carga.movil

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import uy.carga.movil.datos.AccionNoPermitidaException
import uy.carga.movil.datos.RepositorioViajes
import uy.carga.movil.datos.ResultadoRefresco
import uy.carga.movil.datos.ResultadoSync
import uy.carga.movil.datos.Ubicacion
import uy.carga.movil.datos.remoto.ResultadoEnvio
import uy.carga.movil.datos.remoto.ViajeDto
import uy.carga.movil.dominio.TipoEvento
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

class RepositorioViajesTest {

    private lateinit var viajes: ViajeDaoFalso
    private lateinit var eventos: EventoDaoFalso
    private lateinit var central: CentralFalso
    private lateinit var sesion: SesionFalsa
    private lateinit var fotos: FotosFalsas
    private lateinit var programador: ProgramadorFalso
    private lateinit var repo: RepositorioViajes
    private var reloj = 1_000L
    private var ids = 0

    @Before
    fun setUp() {
        viajes = ViajeDaoFalso()
        eventos = EventoDaoFalso()
        central = CentralFalso()
        sesion = SesionFalsa()
        fotos = FotosFalsas()
        programador = ProgramadorFalso()
        repo = RepositorioViajes(
            viajes, eventos, central, sesion, fotos, programador,
            ahora = { reloj++ },
            ahoraConZona = { OffsetDateTime.of(2026, 10, 11, 21, 30, 0, 0, ZoneOffset.ofHours(-3)) },
            nuevoId = { "00000000-0000-0000-0000-00000000000${++ids}" },
        )
        central.guias["GV-1"] = ViajeDto("GV-1", 1, "Transportes del Este", "Agro", "Granos a granel", 18000, "2026-10-12", "Montevideo", "Paysandu", "SBA 1234", null, "ASIGNADA", 0)
        central.guias["GV-2"] = ViajeDto("GV-2", 1, "Transportes del Este", "Forestal", "Rollizos", 24000, "2026-10-13", "Rivera", "Montevideo", "SAB 5678", null, "ASIGNADA", 0)
    }

    @Test
    fun refrescarGuardaLosViajesYSinConexionConservaLosLocales() = runTest {
        assertEquals(ResultadoRefresco.Ok, repo.refrescar())
        assertEquals(listOf("GV-1", "GV-2"), repo.observarViajes().first().map { it.id })
        assertEquals("Granos a granel", repo.observarViajes().first().first().tipoCarga)
        val actualizado = sesion.ultimaActualizacion
        assertTrue(actualizado != null)

        central.sinRed = true
        assertEquals(ResultadoRefresco.SinConexion, repo.refrescar())
        assertEquals(2, repo.observarViajes().first().size) // se sigue viendo lo descargado
        assertEquals(actualizado, sesion.ultimaActualizacion) // "datos del <fecha>" no cambia
    }

    @Test
    fun sinViajesListaVaciaYSesionVencida() = runTest {
        central.guias.clear()
        assertEquals(ResultadoRefresco.Ok, repo.refrescar())
        assertTrue(repo.observarViajes().first().isEmpty())
        central.tokenValido = "otro"
        assertEquals(ResultadoRefresco.SesionVencida, repo.refrescar())
    }

    @Test
    fun registrarSinConexionQuedaPendienteConEstadoOptimista() = runTest {
        repo.refrescar()
        central.sinRed = true
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO, ubicacion = Ubicacion(-34.9, -56.1))

        val e = eventos.buscar(id)
        assertEquals("PENDIENTE", e.estado)
        assertEquals("2026-10-11T21:30:00-03:00", e.fechaHora)
        assertEquals(-34.9, e.latitud!!, 0.0)
        assertEquals(listOf(false), programador.llamadas) // se agenda el envio con WorkManager
        val v = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("EN_CURSO", v.estado) // optimista
        assertEquals("ASIGNADA", v.estadoServidor)
        assertEquals(1, v.eventosPendientes)

        assertEquals(ResultadoSync.REINTENTAR, repo.sincronizarPendientes())
        assertEquals("PENDIENTE", eventos.buscar(id).estado)
        assertEquals(1, eventos.buscar(id).intentos)

        // Vuelve la red: se envia, queda SINCRONIZADO y el estado del servidor se actualiza
        central.sinRed = false
        central.responder(aceptado("EN_CURSO", 1))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals("SINCRONIZADO", eventos.buscar(id).estado)
        val despues = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("EN_CURSO", despues.estadoServidor)
        assertEquals(1L, despues.version)
        assertEquals(0, despues.eventosPendientes)
    }

    @Test
    fun soloSePermitenLasAccionesDelEstado() = runTest {
        repo.refrescar()
        for (tipo in listOf(TipoEvento.FIN, TipoEvento.CARGA_PARCIAL, TipoEvento.INCIDENTE)) {
            try {
                repo.registrarEvento("GV-1", tipo, "algo")
                fail("$tipo no deberia permitirse en ASIGNADA")
            } catch (e: AccionNoPermitidaException) {
                // ok
            }
        }
        repo.registrarEvento("GV-1", TipoEvento.INICIO)
        try {
            repo.registrarEvento("GV-1", TipoEvento.INICIO)
            fail("doble INICIO")
        } catch (e: AccionNoPermitidaException) {
            // ok
        }
        try {
            repo.registrarEvento("GV-1", TipoEvento.INCIDENTE, "  ")
            fail("incidente sin descripcion")
        } catch (e: AccionNoPermitidaException) {
            // ok
        }
        assertEquals(1, eventos.datos.value.size)
    }

    // MO-03: envío de los cinco tipos en orden, con sus datos y versiones.
    @Test
    fun losPendientesSeEnvianEnOrdenConFotoYRespuestaDelCentral() = runTest {
        repo.refrescar()
        central.sinRed = true
        val ids = registrarRecorrido()
        val guardados = eventos.deGuia("GV-1")
        central.sinRed = false
        central.responder(
            aceptado("EN_CURSO", 1), aceptado("EN_CURSO", 1), aceptado("EN_CURSO", 1),
            aceptado("EN_CURSO", 1), aceptado("FINALIZADA", 2),
        )
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(ids, central.recibidos.map { it.idEvento })
        assertEquals(listOf(0L, 1L, 1L, 1L, 1L), central.recibidos.map { it.versionGuia })
        guardados.zip(central.recibidos).forEach { (local, enviado) ->
            assertEquals(local.idGuia, enviado.idGuia)
            assertEquals(local.tipo, enviado.tipo)
            assertEquals(local.fechaHora, enviado.fechaHora)
            assertEquals(local.descripcion, enviado.descripcion)
            assertEquals(local.latitud, enviado.latitud)
            assertEquals(local.longitud, enviado.longitud)
            assertEquals("SINCRONIZADO", eventos.buscar(local.idEvento).estado)
        }
        assertArrayEquals(JPEG, Base64.getDecoder().decode(central.recibidos[3].foto))
        assertTrue(fotos.archivos.isEmpty())
        assertEquals(0, repo.cantidadPendientes())
        val finalizado = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("FINALIZADA", finalizado.estadoServidor)
        assertEquals(2L, finalizado.version)
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(5, central.recibidos.size)
        central.guias.remove("GV-1") // respuesta de GET sin la Guía finalizada
        repo.refrescar()
        assertEquals(listOf("GV-2"), repo.observarViajes().first().map { it.id })
    }

    @Test
    fun eventoYaAplicadoEnElCentralQuedaSincronizadoSinEfecto() = runTest {
        repo.refrescar()
        // Otro telefono ya inicio el viaje en el central (caso 3 de la politica)
        central.guias["GV-1"] = central.guias["GV-1"]!!.copy(estado = "EN_CURSO", version = 1)
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        central.responder(aceptado("EN_CURSO", 1).copy(sinEfecto = true, codigo = "VIAJE_YA_INICIADO"))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        val e = eventos.buscar(id)
        assertEquals("SINCRONIZADO", e.estado)
        assertTrue(e.motivo!!.startsWith("Sin cambios"))
        assertEquals("EN_CURSO", repo.observarViajes().first().first { it.id == "GV-1" }.estado)
    }

    @Test
    fun conflictoSeRegistraNoSeReintentaYRevierteElEstadoOptimista() = runTest {
        repo.refrescar()
        central.guias["GV-1"] = central.guias["GV-1"]!!.copy(estado = "EN_CURSO", version = 1)
        repo.refrescar()
        // la guia se finalizo desde otro lado mientras este telefono estaba sin conexion
        central.guias["GV-1"] = central.guias["GV-1"]!!.copy(estado = "FINALIZADA", version = 2)
        val id = repo.registrarEvento("GV-1", TipoEvento.CARGA_PARCIAL)
        central.responder(ResultadoEnvio.Conflicto("VIAJE_FINALIZADO", "El viaje ya finalizó", central.guias["GV-1"]))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals("CONFLICTO", eventos.buscar(id).estado)
        assertEquals(1, central.recibidos.size)
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(1, central.recibidos.size) // no se reenvia
        val v = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("FINALIZADA", v.estadoServidor) // se actualizo con la guia que devolvio el central
        assertEquals(1, v.eventosRechazados)
    }

    @Test
    fun guiaModificadaPorLaEmpresaMarcaElAvisoYSeEnviaLaVersion() = runTest {
        repo.refrescar()
        central.guias["GV-1"] = central.guias["GV-1"]!!.copy(destino = "Salto", version = 5)
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        central.responder(aceptado("EN_CURSO", 6).copy(guiaModificada = true))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals("SINCRONIZADO", eventos.buscar(id).estado)
        assertEquals(0L, central.recibidos.first { it.idEvento == id }.versionGuia)
        val v = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("Salto", v.destino)
        assertTrue(v.avisoModificada)
        repo.descartarAviso("GV-1")
        assertTrue(!repo.observarViajes().first().first { it.id == "GV-1" }.avisoModificada)
    }

    // MO-05: verifica el reenvío del cliente; la unicidad real se prueba con H2 en central.
    @Test
    fun siSePierdeLaRespuestaElReenvioConservaElMismoEvento() = runTest {
        repo.refrescar()
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        val original = eventos.buscar(id)
        central.responder(ResultadoEnvio.Transitorio("Respuesta perdida"), aceptado("EN_CURSO", 1).copy(duplicado = true))
        assertEquals(ResultadoSync.REINTENTAR, repo.sincronizarPendientes())
        assertEquals(original.copy(intentos = 1, motivo = "Respuesta perdida"), eventos.buscar(id))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(listOf(id, id), central.recibidos.map { it.idEvento })
        assertEquals(central.recibidos[0], central.recibidos[1])
        assertEquals("SINCRONIZADO", eventos.buscar(id).estado)
        assertEquals(1L, viajes.buscar("GV-1")!!.version)
        assertEquals(0, repo.cantidadPendientes())
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(2, central.recibidos.size)
    }

    @Test
    fun sesionVencidaCortaElEnvioYConservaLosPendientes() = runTest {
        repo.refrescar()
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        central.tokenValido = "nuevo"
        assertEquals(ResultadoSync.SESION_VENCIDA, repo.sincronizarPendientes())
        assertEquals("PENDIENTE", eventos.buscar(id).estado)
    }

    @Test
    fun refrescarNoBorraViajesConEventosPendientes() = runTest {
        repo.refrescar()
        central.sinRed = true
        repo.registrarEvento("GV-1", TipoEvento.INICIO)
        central.sinRed = false
        central.guias.remove("GV-1") // por ejemplo, el central ya no lo devuelve
        repo.refrescar()
        assertEquals(listOf("GV-1", "GV-2"), repo.observarViajes().first().map { it.id })
    }

    @Test
    fun borrarDatosLocales() = runTest {
        repo.refrescar()
        repo.registrarEvento("GV-1", TipoEvento.INICIO)
        repo.borrarDatosLocales()
        assertTrue(repo.observarViajes().first().isEmpty())
        assertEquals(0, repo.cantidadPendientes())
        assertNull(sesion.ultimaActualizacion)
    }

    // MO-02: los eventos se guardan antes del envío, con su identidad y evidencia.
    @Test
    fun recorridoOfflineConservaLosCincoEventosYTodosSusDatos() = runTest {
        repo.refrescar()
        central.sinRed = true
        val ids = registrarRecorrido()
        val guardados = eventos.deGuia("GV-1")
        assertEquals(5, ids.toSet().size)
        ids.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        assertEquals(ids, guardados.map { it.idEvento })
        assertEquals(TipoEvento.entries.map { it.name }, guardados.map { it.tipo })
        guardados.forEach {
            assertEquals("GV-1", it.idGuia)
            assertEquals("PENDIENTE", it.estado)
            assertEquals("2026-10-11T21:30:00-03:00", it.fechaHora)
            assertEquals(0, it.intentos)
        }
        assertTrue(guardados.zipWithNext().all { (a, b) -> a.creado < b.creado })
        val incidente = guardados[3]
        assertEquals("Pinchadura", incidente.descripcion)
        assertEquals(-34.9, incidente.latitud!!, 0.0)
        assertEquals(-56.1, incidente.longitud!!, 0.0)
        assertArrayEquals(JPEG, fotos.leer(incidente.rutaFoto!!))
        assertEquals(listOf(false, false, false, false, false), programador.llamadas)
        assertTrue(central.recibidos.isEmpty())
        val viaje = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("FINALIZADA", viaje.estado)
        assertEquals("ASIGNADA", viaje.estadoServidor)
        assertEquals(5, viaje.eventosPendientes)
    }

    // MO-04: no se envía el FIN mientras sigue pendiente el incidente que falló.
    @Test
    fun fallaEnMedioDeLaColaConservaLosPendientesYContinuaEnOrden() = runTest {
        repo.refrescar()
        val inicio = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        val incidente = repo.registrarEvento("GV-1", TipoEvento.INCIDENTE, "Pinchadura", JPEG)
        val fin = repo.registrarEvento("GV-1", TipoEvento.FIN)
        val originales = eventos.deGuia("GV-1")
        central.responder(aceptado("EN_CURSO", 1), ResultadoEnvio.Transitorio("Sin conexión"))

        assertEquals(ResultadoSync.REINTENTAR, repo.sincronizarPendientes())
        assertEquals(listOf(inicio, incidente), central.recibidos.map { it.idEvento })
        assertEquals("SINCRONIZADO", eventos.buscar(inicio).estado)
        assertEquals(listOf(incidente, fin), eventos.deGuia("GV-1").filter { it.estado == "PENDIENTE" }.map { it.idEvento })
        assertEquals(originales[1].copy(intentos = 1, motivo = "Sin conexión"), eventos.buscar(incidente))
        assertEquals(originales[2], eventos.buscar(fin))
        assertArrayEquals(JPEG, fotos.leer(eventos.buscar(incidente).rutaFoto!!))

        central.responder(aceptado("EN_CURSO", 1), aceptado("FINALIZADA", 2))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(listOf(inicio, incidente, incidente, fin), central.recibidos.map { it.idEvento })
        assertEquals(central.recibidos[1], central.recibidos[2])
        assertTrue(eventos.deGuia("GV-1").all { it.estado == "SINCRONIZADO" })
        assertEquals(0, repo.cantidadPendientes())
        assertTrue(fotos.archivos.isEmpty())
    }

    // MO-06.2
    @Test
    fun guiaCanceladaQuedaEnConflictoYNoSeReenvia() = runTest {
        repo.refrescar()
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        val cancelada = central.guias.getValue("GV-1").copy(estado = "CANCELADA", version = 1)
        central.responder(ResultadoEnvio.Conflicto("GUIA_CANCELADA", "La empresa canceló la guía", cancelada))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals("CONFLICTO", eventos.buscar(id).estado)
        assertEquals("La empresa canceló la guía.", eventos.buscar(id).motivo)
        assertEquals("CANCELADA", viajes.buscar("GV-1")!!.estadoServidor)
        assertEquals(0, repo.cantidadPendientes())
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(listOf(id), central.recibidos.map { it.idEvento })
        central.guias.remove("GV-1")
        repo.refrescar()
        assertNull(viajes.buscar("GV-1"))
    }

    // MO-06.3: la respuesta no revela los datos de la Guía del nuevo chofer.
    @Test
    fun guiaReasignadaQuedaEnConflictoSinInventarDatosDelNuevoChofer() = runTest {
        repo.refrescar()
        val local = viajes.buscar("GV-1")
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        central.responder(ResultadoEnvio.Conflicto("GUIA_REASIGNADA", "La guía fue reasignada", guia = null))
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals("CONFLICTO", eventos.buscar(id).estado)
        assertEquals("La empresa reasignó la guía a otro chofer.", eventos.buscar(id).motivo)
        assertEquals(local, viajes.buscar("GV-1"))
        assertEquals(0, repo.cantidadPendientes())
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(listOf(id), central.recibidos.map { it.idEvento })
        central.guias.remove("GV-1")
        repo.refrescar()
        assertNull(viajes.buscar("GV-1"))
    }

    private suspend fun registrarRecorrido(): List<String> = listOf(
        repo.registrarEvento("GV-1", TipoEvento.INICIO),
        repo.registrarEvento("GV-1", TipoEvento.CARGA_PARCIAL),
        repo.registrarEvento("GV-1", TipoEvento.DESCARGA_PARCIAL),
        repo.registrarEvento("GV-1", TipoEvento.INCIDENTE, " Pinchadura ", JPEG, Ubicacion(-34.9, -56.1)),
        repo.registrarEvento("GV-1", TipoEvento.FIN),
    )

    private fun aceptado(estado: String, version: Long) = ResultadoEnvio.Aceptado(
        estadoGuia = estado, version = version, duplicado = false,
        guia = central.guias.getValue("GV-1").copy(estado = estado, version = version),
    )

    companion object {
        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
    }
}
