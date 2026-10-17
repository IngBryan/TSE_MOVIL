package uy.carga.movil

import android.app.Application
import androidx.room.Room
import uy.carga.movil.datos.AlmacenFotosArchivos
import uy.carga.movil.datos.RepositorioViajes
import uy.carga.movil.datos.local.BaseLocal
import uy.carga.movil.datos.local.MIGRACION_1_2
import uy.carga.movil.datos.local.MIGRACION_2_3
import uy.carga.movil.datos.remoto.ApiMovilHttp
import uy.carga.movil.sesion.AlmacenSesionCifrado
import uy.carga.movil.sesion.ConfigGubUy
import uy.carga.movil.sesion.ReceptorLoopback
import uy.carga.movil.sesion.RepositorioAuth
import uy.carga.movil.sync.ProgramadorWorkManager

// Dependencias de la app (inyeccion manual).
class Contenedor(app: Application) {
    private val base = Room.databaseBuilder(app, BaseLocal::class.java, "cargauy.db")
        .addMigrations(MIGRACION_1_2, MIGRACION_2_3)
        .build()
    val sesion = AlmacenSesionCifrado(app)
    private val api = ApiMovilHttp(BuildConfig.BASE_URL)
    val programador = ProgramadorWorkManager(app)
    val viajes = RepositorioViajes(
        viajes = base.viajes(),
        eventos = base.eventos(),
        api = api,
        sesion = sesion,
        fotos = AlmacenFotosArchivos(app),
        programador = programador,
    )
    // Con la redirect URI loopback (http://localhost:8080) la vuelta de gub.uy la recibe un servidor
    // HTTP minimo en el telefono; con uy.carga.movil:/oauth2redirect llega a MainActivity.
    val usaLoopback = BuildConfig.OIDC_REDIRECT.startsWith("http://")
    val receptor = ReceptorLoopback(ReceptorLoopback.puertoDe(BuildConfig.OIDC_REDIRECT))
    val auth = RepositorioAuth(
        gubUy = ConfigGubUy(
            authorizeUrl = BuildConfig.OIDC_AUTHORIZE,
            clientId = BuildConfig.OIDC_CLIENT_ID,
            scopes = BuildConfig.OIDC_SCOPES,
            redirectUri = BuildConfig.OIDC_REDIRECT,
        ),
        api = api,
        sesion = sesion,
        borrarDatosLocales = viajes::borrarDatosLocales,
        // Al volver a ingresar se envia lo que haya quedado pendiente con la sesion vencida
        alIngresar = { programador.programar(urgente = true) },
    )
}

class CargaApp : Application() {
    lateinit var contenedor: Contenedor
        private set

    override fun onCreate() {
        super.onCreate()
        contenedor = Contenedor(this)
    }
}
