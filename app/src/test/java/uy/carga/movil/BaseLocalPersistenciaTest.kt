package uy.carga.movil

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import uy.carga.movil.datos.AlmacenFotosArchivos
import uy.carga.movil.datos.RepositorioViajes
import uy.carga.movil.datos.ResultadoRefresco
import uy.carga.movil.datos.Ubicacion
import uy.carga.movil.datos.local.BaseLocal
import uy.carga.movil.datos.remoto.ViajeDto
import uy.carga.movil.dominio.TipoEvento
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class BaseLocalPersistenciaTest {

    // MO-01: usa Room y SQLite en archivo, no un DAO falso ni una base en memoria.
    @Test
    fun guiaYPendientesConFotoSobrevivenAlCerrarYReabrirSinConexion() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val nombre = "persistencia-${UUID.randomUUID()}.db"
        fun abrir() = Room.databaseBuilder(context, BaseLocal::class.java, nombre)
            .allowMainThreadQueries().build()
        val central = CentralFalso()
        central.guias["GV-1"] = ViajeDto(
            "GV-1", 1, "Transportes del Este", "Agro", "Granos", 18000,
            "2026-10-12", "Montevideo", "Paysandú", "SBA 1234", "Scania", "ASIGNADA", 0,
        )
        var reloj = 1000L
        fun repositorio(base: BaseLocal) = RepositorioViajes(
            base.viajes(), base.eventos(), central, SesionFalsa(),
            AlmacenFotosArchivos(context), ProgramadorFalso(),
            ahora = { reloj++ },
            ahoraConZona = { OffsetDateTime.parse("2026-10-11T21:30:00-03:00") },
        )
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        var base = abrir()
        var rutaFoto: String? = null
        try {
            val original = repositorio(base)
            assertEquals(ResultadoRefresco.Ok, original.refrescar())
            central.sinRed = true
            val inicio = original.registrarEvento("GV-1", TipoEvento.INICIO)
            val incidente = original.registrarEvento(
                "GV-1", TipoEvento.INCIDENTE, "Pinchadura", jpeg, Ubicacion(-34.9, -56.1),
            )
            val guiaGuardada = base.viajes().buscar("GV-1")!!
            val eventosGuardados = base.eventos().deGuia("GV-1")
            rutaFoto = eventosGuardados[1].rutaFoto
            assertEquals(listOf(inicio, incidente), eventosGuardados.map { it.idEvento })
            assertTrue(context.getDatabasePath(nombre).isFile)

            base.close()
            base = abrir()
            val reabierto = repositorio(base)

            assertEquals(guiaGuardada, base.viajes().buscar("GV-1"))
            assertEquals(eventosGuardados, base.eventos().deGuia("GV-1"))
            assertArrayEquals(jpeg, AlmacenFotosArchivos(context).leer(rutaFoto!!))
            assertEquals(ResultadoRefresco.SinConexion, reabierto.refrescar())
            val (viaje, pendientes) = reabierto.observarViaje("GV-1").first()
            assertEquals("Paysandú", viaje!!.destino)
            assertEquals("ASIGNADA", viaje.estadoServidor)
            assertEquals("EN_CURSO", viaje.estado)
            assertEquals(2, viaje.eventosPendientes)
            assertEquals(eventosGuardados, pendientes)
            assertTrue(pendientes.all { it.estado == "PENDIENTE" })
            assertTrue(central.recibidos.isEmpty())
        } finally {
            base.close()
            context.deleteDatabase(nombre)
            rutaFoto?.let { AlmacenFotosArchivos(context).borrar(it) }
        }
    }
}
