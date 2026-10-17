package uy.carga.movil

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import uy.carga.movil.datos.AlmacenFotos
import uy.carga.movil.datos.ProgramadorSync
import uy.carga.movil.datos.local.EventoDao
import uy.carga.movil.datos.local.EventoEntity
import uy.carga.movil.datos.local.ViajeDao
import uy.carga.movil.datos.local.ViajeEntity
import uy.carga.movil.datos.remoto.ApiMovil
import uy.carga.movil.datos.remoto.ErrorLoginException
import uy.carga.movil.datos.remoto.EventoDto
import uy.carga.movil.datos.remoto.ResultadoEnvio
import uy.carga.movil.datos.remoto.SinConexionException
import uy.carga.movil.datos.remoto.TokenDto
import uy.carga.movil.datos.remoto.UsuarioDto
import uy.carga.movil.datos.remoto.ViajeDto
import uy.carga.movil.sesion.AlmacenSesion
import uy.carga.movil.sesion.LoginPendiente
import uy.carga.movil.sesion.Sesion

// Dobles en memoria de Room, la API del central, la sesion y WorkManager.

class ViajeDaoFalso : ViajeDao {
    val datos = MutableStateFlow<List<ViajeEntity>>(emptyList())
    override fun observar(): Flow<List<ViajeEntity>> = datos
    override suspend fun todos() = datos.value
    override suspend fun buscar(id: String) = datos.value.firstOrNull { it.id == id }
    override suspend fun guardar(viajes: List<ViajeEntity>) {
        datos.value = (datos.value.filter { v -> viajes.none { it.id == v.id } } + viajes).sortedBy { it.fecha + it.id }
    }
    override suspend fun actualizarEstado(id: String, estado: String, version: Long?) {
        datos.value = datos.value.map { if (it.id == id) it.copy(estadoServidor = estado, version = version) else it }
    }
    override suspend fun marcarAviso(id: String, aviso: Boolean) {
        datos.value = datos.value.map { if (it.id == id) it.copy(avisoModificada = aviso) else it }
    }
        override suspend fun borrar(ids: List<String>) {
        datos.value = datos.value.filter { it.id !in ids }
    }
    override suspend fun borrarTodo() {
        datos.value = emptyList()
    }
}

class EventoDaoFalso : EventoDao {
    val datos = MutableStateFlow<List<EventoEntity>>(emptyList())
    fun buscar(id: String) = datos.value.first { it.idEvento == id }
    override fun observar(): Flow<List<EventoEntity>> = datos
    override suspend fun deGuia(idGuia: String) = datos.value.filter { it.idGuia == idGuia }.sortedBy { it.creado }
    override suspend fun primerPendiente() = datos.value.filter { it.estado == "PENDIENTE" }.minByOrNull { it.creado }
    override suspend fun cantidadPendientes() = datos.value.count { it.estado == "PENDIENTE" }
    override suspend fun insertar(evento: EventoEntity) {
        require(datos.value.none { it.idEvento == evento.idEvento }) { "clave duplicada" }
        datos.value = datos.value + evento
    }
    override suspend fun marcar(idEvento: String, estado: String, motivo: String?, rutaFoto: String?) {
        datos.value = datos.value.map { if (it.idEvento == idEvento) it.copy(estado = estado, motivo = motivo, rutaFoto = rutaFoto) else it }
    }
    override suspend fun registrarIntento(idEvento: String, motivo: String?) {
        datos.value = datos.value.map { if (it.idEvento == idEvento) it.copy(intentos = it.intentos + 1, motivo = motivo) else it }
    }
    override suspend fun borrarDeGuias(idsGuia: List<String>) {
        datos.value = datos.value.filter { it.idGuia !in idsGuia }
    }
    override suspend fun borrarTodo() {
        datos.value = emptyList()
    }
}

// Simula el central: aplica las mismas transiciones e idempotencia por idEvento.
class CentralFalso : ApiMovil {
    val guias = linkedMapOf<String, ViajeDto>()
    val procesados = mutableMapOf<String, ResultadoEnvio>()
    val recibidos = mutableListOf<EventoDto>()
    var sinRed = false
    var tokenValido = "token-ok"
    // Simula que la respuesta se pierde despues de aplicar el evento (el movil no se entera)
    var perderRespuestaUnaVez = false

    // Ultimo canje recibido: code, codeVerifier, nonce, redirectUri
    var ultimoCanje: List<String>? = null
    // Si no es null, el servidor rechaza el ingreso con este motivo
    var rechazoLogin: String? = null

    override suspend fun canjearCodigo(code: String, codeVerifier: String, nonce: String, redirectUri: String): TokenDto {
        ultimoCanje = listOf(code, codeVerifier, nonce, redirectUri)
        rechazoLogin?.let { throw ErrorLoginException(it) }
        return TokenDto("token-ok", 4_000_000_000, UsuarioDto("56087406", "Bryan"))
    }

    override suspend fun viajes(token: String): List<ViajeDto> {
        if (sinRed) throw SinConexionException("sin red")
        if (token != tokenValido) throw uy.carga.movil.datos.remoto.SesionVencidaException()
        return guias.values.filter { it.estado == "ASIGNADA" || it.estado == "EN_CURSO" }
    }

    override suspend fun enviarEvento(token: String, evento: EventoDto): ResultadoEnvio {
        if (sinRed) return ResultadoEnvio.Transitorio("sin red")
        if (token != tokenValido) return ResultadoEnvio.SesionVencida
        recibidos += evento
        procesados[evento.idEvento]?.let {
            return when (it) {
                is ResultadoEnvio.Aceptado -> it.copy(duplicado = true)
                else -> it
            }
        }
        val g = guias[evento.idGuia] ?: return ResultadoEnvio.Rechazado("no_encontrada", "No existe")
        if (g.estado == "REASIGNADA") {
            return ResultadoEnvio.Conflicto("GUIA_REASIGNADA", "Reasignada").also { procesados[evento.idEvento] = it }
        }
        val modificada = evento.versionGuia != null && evento.versionGuia != g.version
        // Misma politica que ViajesChoferService.decidir: (nuevo estado, sin efecto, codigo de conflicto)
        val (nuevo, sinEfecto, conflicto) = when (evento.tipo) {
            "INICIO" -> when (g.estado) {
                "ASIGNADA" -> Triple("EN_CURSO", false, null)
                "EN_CURSO" -> Triple(g.estado, true, null)
                else -> Triple(g.estado, false, "VIAJE_FINALIZADO")
            }
            "FIN" -> when (g.estado) {
                "EN_CURSO" -> Triple("FINALIZADA", false, null)
                "FINALIZADA" -> Triple(g.estado, true, null)
                else -> Triple(g.estado, false, "VIAJE_NO_INICIADO")
            }
            else -> when (g.estado) {
                "EN_CURSO" -> Triple(g.estado, false, null)
                "ASIGNADA" -> Triple(g.estado, false, "VIAJE_NO_INICIADO")
                else -> Triple(g.estado, false, "VIAJE_FINALIZADO")
            }
        }
        if (conflicto != null) {
            return ResultadoEnvio.Conflicto(conflicto, "No aplica", g).also { procesados[evento.idEvento] = it }
        }
        val version = (g.version ?: 0) + if (nuevo != g.estado) 1 else 0
        val actual = g.copy(estado = nuevo, version = version)
        guias[g.id] = actual
        val r = ResultadoEnvio.Aceptado(
            nuevo, version, false, sinEfecto, if (sinEfecto) "VIAJE_YA_INICIADO" else null, null, modificada, actual,
        )
        procesados[evento.idEvento] = r
        if (perderRespuestaUnaVez) {
            perderRespuestaUnaVez = false
            return ResultadoEnvio.Transitorio("timeout")
        }
        return r
    }
}

class SesionFalsa(token: String? = "token-ok") : AlmacenSesion {
    private val estado = MutableStateFlow(token?.let { Sesion(it, 4_000_000_000, "56087406", "Bryan") })
    override val sesion: StateFlow<Sesion?> = estado
    override fun guardar(sesion: Sesion) {
        estado.value = sesion
    }
    override fun borrar() {
        estado.value = null
    }
    override var loginPendiente: LoginPendiente? = null
    override var ultimaActualizacion: Long? = null
    override fun ultimaCedula(): String? = estado.value?.cedula
}

class FotosFalsas : AlmacenFotos {
    val archivos = mutableMapOf<String, ByteArray>()
    override fun guardar(idEvento: String, jpeg: ByteArray) = "fotos/$idEvento.jpg".also { archivos[it] = jpeg }
    override fun leer(ruta: String) = archivos[ruta]
    override fun borrar(ruta: String) {
        archivos.remove(ruta)
    }
}

class ProgramadorFalso : ProgramadorSync {
    val llamadas = mutableListOf<Boolean>()
    override fun programar(urgente: Boolean) {
        llamadas += urgente
    }
}
