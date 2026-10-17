package uy.carga.movil.sesion

import uy.carga.movil.datos.remoto.ApiMovil
import uy.carga.movil.datos.remoto.ErrorLoginException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

sealed interface ResultadoLogin {
    data class Ok(val nombre: String) : ResultadoLogin
    data class Error(val mensaje: String) : ResultadoLogin
}

// Cliente de ID Uruguay que usa la app. El client_id es publico; el secret lo tiene solo el servidor.
data class ConfigGubUy(
    val authorizeUrl: String,
    val clientId: String,
    val scopes: String,
    // Servidor loopback de la app (ReceptorLoopback): una de las redirect URIs registradas en ID Uruguay
    val redirectUri: String,
)

// Login directo contra gub.uy (RFC 8252, app nativa):
// 1. La app genera code_verifier (PKCE S256), state y nonce, y abre el authorize de ID Uruguay en un
//    Custom Tab con su redirect_uri: uy.carga.movil:/oauth2redirect (la recibe MainActivity) o, mientras
//    no este registrada, el loopback http://localhost:8080 (ReceptorLoopback en el telefono).
// 2. El chofer ingresa en gub.uy, que vuelve a la redirect_uri con ?code=...&state=...
// 3. La app manda code + code_verifier + nonce al componente central (WildFly), POST
//    BASE_URL/api/movil/auth/token. El central canjea el code con el client secret, valida el id_token
//    y que sea chofer habilitado (PDI, roles) y devuelve el token de la app (8 h).
class RepositorioAuth(
    private val gubUy: ConfigGubUy,
    private val api: ApiMovil,
    private val sesion: AlmacenSesion,
    private val borrarDatosLocales: suspend () -> Unit,
    private val alIngresar: () -> Unit,
) {

    fun urlLogin(): String {
        val pendiente = LoginPendiente(verifier = aleatorio(), state = aleatorio(), nonce = aleatorio())
        sesion.loginPendiente = pendiente
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to gubUy.clientId,
            "redirect_uri" to gubUy.redirectUri,
            "scope" to gubUy.scopes,
            "state" to pendiente.state,
            "nonce" to pendiente.nonce,
            "code_challenge" to challenge(pendiente.verifier),
            "code_challenge_method" to "S256",
            // Pide usuario y contraseña siempre, aunque Chrome tenga la sesion de gub.uy abierta: en un
            // telefono compartido no se entra con la cuenta del chofer anterior
            "prompt" to "login",
        )
        return gubUy.authorizeUrl + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${codificar(v)}" }
    }

    // Lo que trajo gub.uy a la redirect loopback: ?code=...&state=... o ?error=...
    suspend fun completarLogin(code: String?, state: String?, error: String?): ResultadoLogin {
        if (error != null) return ResultadoLogin.Error(mensaje(if (error == "access_denied") "cancelado" else "error_idp"))
        val pendiente = sesion.loginPendiente
        if (pendiente == null || state == null || state != pendiente.state) return ResultadoLogin.Error(mensaje("estado_invalido"))
        if (code == null) return ResultadoLogin.Error(mensaje("error_idp"))
        sesion.loginPendiente = null // el code de gub.uy es de un solo uso
        return try {
            val r = api.canjearCodigo(code, pendiente.verifier, pendiente.nonce, gubUy.redirectUri)
            // Si ingresa otra persona en el mismo telefono, no ve los viajes del anterior
            if (sesion.ultimaCedula() != null && sesion.ultimaCedula() != r.usuario.cedula) borrarDatosLocales()
            sesion.guardar(Sesion(r.token, r.expira, r.usuario.cedula, r.usuario.nombre))
            alIngresar()
            ResultadoLogin.Ok(r.usuario.nombre)
        } catch (e: ErrorLoginException) {
            ResultadoLogin.Error(mensaje(e.motivo))
        } catch (e: Exception) {
            ResultadoLogin.Error(e.message ?: "No se pudo completar el ingreso.")
        }
    }

    suspend fun salir() {
        borrarDatosLocales()
        sesion.borrar()
    }

    companion object {
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

        private fun aleatorio(): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

        private fun codificar(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

        private fun mensaje(motivo: String?) = MENSAJES[motivo] ?: MENSAJES["error_idp"]!!

        // Motivos del error: los de la app y los que devuelve POST /api/movil/auth/token
        val MENSAJES = mapOf(
            "cancelado" to "No se completó el ingreso. Podés volver a intentarlo.",
            "no_chofer" to "Tu usuario no está habilitado como chofer en ninguna empresa.",
            "no_existe" to "No se pudo verificar tu identidad. Comunicate con el MTOP.",
            "menor_de_edad" to "No cumplís con la edad mínima para usar carga.uy.",
            "ingreso_no_disponible" to "El ingreso no está disponible. Probá de nuevo en unos minutos.",
            "estado_invalido" to "La solicitud de ingreso venció. Volvé a intentarlo.",
            "token_invalido" to "No pudimos validar tu identidad con gub.uy. Volvé a intentarlo.",
            "idp_no_disponible" to "gub.uy no está disponible en este momento.",
            "error_idp" to "gub.uy devolvió un error al ingresar. Volvé a intentarlo.",
            "puerto_ocupado" to "No se pudo preparar el ingreso (el puerto 8080 del teléfono está en uso). Cerrá otras apps y volvé a intentarlo.",
        )
    }
}
