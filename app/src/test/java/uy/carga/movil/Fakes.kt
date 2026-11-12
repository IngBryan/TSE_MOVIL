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

// Respuestas preparadas por cada prueba: no vuelve a implementar las reglas del central.
class CentralFalso : ApiMovil {
    val guias = linkedMapOf<String, ViajeDto>()
    private val respuestas = ArrayDeque<ResultadoEnvio>()

    fun responder(vararg resultados: ResultadoEnvio) {
        respuestas.addAll(resultados.toList())
    }
    val recibidos = mutableListOf<EventoDto>()
    var sinRed = false
    var tokenValido = "token-ok"

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
        return guias.values.toList()
    }

    override suspend fun enviarEvento(token: String, evento: EventoDto): ResultadoEnvio {
        if (sinRed) return ResultadoEnvio.Transitorio("sin red")
        if (token != tokenValido) return ResultadoEnvio.SesionVencida
        recibidos += evento
        check(respuestas.isNotEmpty()) { "Falta preparar la respuesta de ${evento.tipo}" }
        return respuestas.removeFirst()
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
