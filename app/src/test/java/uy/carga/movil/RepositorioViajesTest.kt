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
import uy.carga.movil.datos.remoto.ViajeDto
import uy.carga.movil.dominio.TipoEvento
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Base64

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
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals("SINCRONIZADO", eventos.buscar(id).estado)
        val despues = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("EN_CURSO", despues.estadoServidor)
        assertEquals(1L, despues.version)
        assertEquals(0, despues.eventosPendientes)
        assertEquals("EN_CURSO", central.guias["GV-1"]!!.estado)
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

    @Test
    fun losPendientesSeEnvianEnOrdenConFotoYRespuestaDelCentral() = runTest {
        repo.refrescar()
        central.sinRed = true
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val inicio = repo.registrarEvento("GV-1", TipoEvento.INICIO)
        val incidente = repo.registrarEvento("GV-1", TipoEvento.INCIDENTE, " Pinchadura ", jpeg)
        val fin = repo.registrarEvento("GV-1", TipoEvento.FIN)
        assertEquals("FINALIZADA", repo.observarViajes().first().first { it.id == "GV-1" }.estado)
        assertEquals(1, fotos.archivos.size)

        central.sinRed = false
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(listOf(inicio, incidente, fin), central.recibidos.map { it.idEvento })
        assertEquals("Pinchadura", central.recibidos[1].descripcion)
        assertArrayEquals(jpeg, Base64.getDecoder().decode(central.recibidos[1].foto))
        assertTrue(fotos.archivos.isEmpty()) // la foto se borra al sincronizar
        assertEquals("FINALIZADA", central.guias["GV-1"]!!.estado)

        // El central ya no devuelve la guia finalizada: se borra del telefono al refrescar
        repo.refrescar()
        assertEquals(listOf("GV-2"), repo.observarViajes().first().map { it.id })
    }

    @Test
    fun eventoYaAplicadoEnElCentralQuedaSincronizadoSinEfecto() = runTest {
        repo.refrescar()
        // Otro telefono ya inicio el viaje en el central (caso 3 de la politica)
        central.guias["GV-1"] = central.guias["GV-1"]!!.copy(estado = "EN_CURSO", version = 1)
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)
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
        repo.sincronizarPendientes()
        assertEquals(0L, central.recibidos.first { it.idEvento == id }.versionGuia)
        val v = repo.observarViajes().first().first { it.id == "GV-1" }
        assertEquals("Salto", v.destino)
        assertTrue(v.avisoModificada)
        repo.descartarAviso("GV-1")
        assertTrue(!repo.observarViajes().first().first { it.id == "GV-1" }.avisoModificada)
    }

    @Test
    fun siSePierdeLaRespuestaElReenvioUsaElMismoIdYNoSeAplicaDosVeces() = runTest {
        repo.refrescar()
        central.perderRespuestaUnaVez = true
        val id = repo.registrarEvento("GV-1", TipoEvento.INICIO)

        assertEquals(ResultadoSync.REINTENTAR, repo.sincronizarPendientes())
        assertEquals("EN_CURSO", central.guias["GV-1"]!!.estado) // el central si lo aplico
        assertEquals(ResultadoSync.COMPLETO, repo.sincronizarPendientes())
        assertEquals(listOf(id, id), central.recibidos.map { it.idEvento })
        assertEquals("SINCRONIZADO", eventos.buscar(id).estado)
        assertEquals(1L, central.guias["GV-1"]!!.version) // una sola transicion
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
}
