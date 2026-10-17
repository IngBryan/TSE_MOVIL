package uy.carga.movil.sesion

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Sesion(val token: String, val expira: Long, val cedula: String, val nombre: String) {
    // expira en epoch segundos
    fun vencida(ahoraMs: Long = System.currentTimeMillis()) = expira * 1000 <= ahoraMs
}

data class LoginPendiente(val verifier: String, val state: String, val nonce: String)

interface AlmacenSesion {
    val sesion: StateFlow<Sesion?>
    fun guardar(sesion: Sesion)
    fun borrar()

    // code_verifier (PKCE), state y nonce del login en curso, hasta que vuelve el code de gub.uy
    var loginPendiente: LoginPendiente?

    // Ultima vez que se descargaron los viajes del central (epoch ms)
    var ultimaActualizacion: Long?

    // Cedula del ultimo usuario que ingreso (para borrar los datos locales si ingresa otro)
    fun ultimaCedula(): String?
}

// Token y datos de la sesion en EncryptedSharedPreferences (clave en el Android Keystore).
class AlmacenSesionCifrado(context: Context) : AlmacenSesion {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "sesion_cargauy",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val estado = MutableStateFlow(leer())
    override val sesion: StateFlow<Sesion?> = estado.asStateFlow()

    private fun leer(): Sesion? {
        val token = prefs.getString("token", null) ?: return null
        return Sesion(
            token = token,
            expira = prefs.getLong("expira", 0),
            cedula = prefs.getString("cedula", "") ?: "",
            nombre = prefs.getString("nombre", "") ?: "",
        )
    }

    override fun guardar(sesion: Sesion) {
        prefs.edit()
            .putString("token", sesion.token)
            .putLong("expira", sesion.expira)
            .putString("cedula", sesion.cedula)
            .putString("nombre", sesion.nombre)
            .apply()
        estado.value = sesion
    }

    override fun borrar() {
        prefs.edit().remove("token").remove("expira").remove("nombre").apply() // la cedula queda para saber de quien son los datos locales
        estado.value = null
    }

    override var loginPendiente: LoginPendiente?
        get() {
            val verifier = prefs.getString("verifier", null) ?: return null
            val state = prefs.getString("state", null) ?: return null
            val nonce = prefs.getString("nonce", null) ?: return null
            return LoginPendiente(verifier, state, nonce)
        }
        set(valor) = prefs.edit()
            .putString("verifier", valor?.verifier)
            .putString("state", valor?.state)
            .putString("nonce", valor?.nonce)
            .apply()

    override var ultimaActualizacion: Long?
        get() = prefs.getLong("ultimaActualizacion", -1).takeIf { it >= 0 }
        set(valor) {
            if (valor == null) prefs.edit().remove("ultimaActualizacion").apply()
            else prefs.edit().putLong("ultimaActualizacion", valor).apply()
        }

    override fun ultimaCedula(): String? = prefs.getString("cedula", null)
}
