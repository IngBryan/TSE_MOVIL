package uy.carga.movil.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uy.carga.movil.CargaApp
import uy.carga.movil.datos.AccionNoPermitidaException
import uy.carga.movil.datos.ResultadoRefresco
import uy.carga.movil.datos.Ubicacion
import uy.carga.movil.datos.local.EventoEntity
import uy.carga.movil.dominio.TipoEvento
import uy.carga.movil.dominio.Viaje
import uy.carga.movil.sesion.RepositorioAuth
import uy.carga.movil.sesion.ResultadoLogin
import uy.carga.movil.sesion.ServicioLoginGubUy
import uy.carga.movil.sesion.Sesion

data class EstadoUi(
    val actualizando: Boolean = false,
    // null = todavia no se intento; false = el ultimo refresco no llego al servidor
    val conectado: Boolean? = null,
    val ultimaActualizacion: Long? = null,
    val noEsChofer: Boolean = false,
    val errorLogin: String? = null,
    val ingresando: Boolean = false,
    val mensaje: String? = null,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val c = (app as CargaApp).contenedor
    val sesion: StateFlow<Sesion?> = c.sesion.sesion
    val viajes: StateFlow<List<Viaje>> =
        c.viajes.observarViajes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(EstadoUi(ultimaActualizacion = c.sesion.ultimaActualizacion))
    val ui: StateFlow<EstadoUi> = _ui.asStateFlow()

    // Al volver la red se refresca y se envian los pendientes sin esperar el backoff
    private val conectividad = app.getSystemService(ConnectivityManager::class.java)
    private val callbackRed = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (sesion.value != null) {
                c.programador.programar(urgente = true)
                refrescar()
            }
        }

        override fun onLost(network: Network) {
            _ui.update { it.copy(conectado = false) }
        }
    }

    init {
        conectividad.registerDefaultNetworkCallback(callbackRed)
        if (!hayRed()) _ui.update { it.copy(conectado = false) }
        // Vuelta del login con gub.uy a http://localhost:8080 (ReceptorLoopback)
        viewModelScope.launch {
            c.receptor.vueltas.collect { completarLogin(it.code, it.state, it.error) }
        }
    }

    override fun onCleared() {
        conectividad.unregisterNetworkCallback(callbackRed)
    }

    fun viaje(id: String): Flow<Pair<Viaje?, List<EventoEntity>>> = c.viajes.observarViaje(id)

    fun refrescar() {
        if (sesion.value == null || _ui.value.actualizando) return
        viewModelScope.launch {
            _ui.update { it.copy(actualizando = true) }
            val r = c.viajes.refrescar()
            _ui.update {
                when (r) {
                    ResultadoRefresco.Ok -> it.copy(conectado = true, noEsChofer = false, ultimaActualizacion = c.sesion.ultimaActualizacion)
                    ResultadoRefresco.SinConexion -> it.copy(conectado = false)
                    // La sesion vencida se muestra con el aviso de la pantalla (sesion.vencida())
                    ResultadoRefresco.SesionVencida -> it.copy(conectado = true)
                    ResultadoRefresco.NoEsChofer -> it.copy(conectado = true, noEsChofer = true)
                    is ResultadoRefresco.Error -> it.copy(conectado = false, mensaje = r.mensaje)
                }.copy(actualizando = false)
            }
            if (r == ResultadoRefresco.Ok && c.viajes.cantidadPendientes() > 0) c.programador.programar(urgente = true)
        }
    }

    fun sincronizarAhora() = c.programador.programar(urgente = true)

    // URL del authorize de gub.uy, o null si no se pudo abrir el servidor loopback que recibe la vuelta
    // (solo se usa si la redirect URI es http://localhost:<puerto>)
    fun urlLogin(): String? {
        if (c.usaLoopback) {
            if (!c.receptor.escuchar()) {
                val error = RepositorioAuth.MENSAJES["puerto_ocupado"]
                _ui.update { it.copy(errorLogin = error, mensaje = error) }
                return null
            }
            ServicioLoginGubUy.iniciar(getApplication())
        }
        _ui.update { it.copy(errorLogin = null) }
        return c.auth.urlLogin()
    }

    // Vuelta por la redirect URI propia (uy.carga.movil:/oauth2redirect), que recibe MainActivity
    fun vueltaGubUy(code: String?, state: String?, error: String?) = completarLogin(code, state, error)

    private fun completarLogin(code: String?, state: String?, error: String?) {
        viewModelScope.launch {
            _ui.update { it.copy(ingresando = true, errorLogin = null) }
            when (val r = c.auth.completarLogin(code, state, error)) {
                is ResultadoLogin.Ok -> {
                    _ui.update { it.copy(ingresando = false, mensaje = "Hola, ${r.nombre.ifBlank { "chofer" }}") }
                    refrescar()
                }
                is ResultadoLogin.Error -> _ui.update { it.copy(ingresando = false, errorLogin = r.mensaje) }
            }
        }
    }

    fun salir() {
        viewModelScope.launch {
            c.auth.salir()
            _ui.value = EstadoUi()
        }
    }

    suspend fun cantidadPendientes() = c.viajes.cantidadPendientes()

    fun registrar(idGuia: String, tipo: TipoEvento, descripcion: String?, foto: ByteArray?) {
        viewModelScope.launch {
            val mensaje = try {
                c.viajes.registrarEvento(idGuia, tipo, descripcion, foto, ultimaUbicacion())
                if (hayRed()) "${tipo.etiqueta}: registrado, enviando…" else "${tipo.etiqueta}: guardado sin conexión, se envía al volver la red."
            } catch (e: AccionNoPermitidaException) {
                e.message
            }
            _ui.update { it.copy(mensaje = mensaje) }
        }
    }

    fun descartarAviso(idGuia: String) {
        viewModelScope.launch { c.viajes.descartarAviso(idGuia) }
    }

    fun mensajeMostrado() = _ui.update { it.copy(mensaje = null) }

    private fun hayRed(): Boolean =
        conectividad.getNetworkCapabilities(conectividad.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    // Ubicacion opcional del evento: la ultima conocida, solo si el chofer dio el permiso
    @SuppressLint("MissingPermission")
    private fun ultimaUbicacion(): Ubicacion? {
        val ctx: Context = getApplication()
        val permitido = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        if (!permitido) return null
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return null
        return runCatching {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
                ?.let { Ubicacion(it.latitude, it.longitude) }
        }.getOrNull()
    }
}
