package uy.carga.movil.datos.remoto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.carga.movil.datos.LogMovil
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

// API REST de la app en el componente central (WildFly, frontofficeciudadanos-web):
// /api/movil/auth/token, /api/movil/viajes y /api/movil/eventos.

@Serializable
data class ViajeDto(
    val id: String,
    val nroEmpresa: Long? = null,
    val empresa: String? = null,
    val rubroCliente: String = "",
    val tipoCarga: String? = null,
    val pesoCargaKg: Long = 0,
    val fecha: String = "",
    val origen: String = "",
    val destino: String = "",
    val matricula: String? = null,
    val vehiculo: String? = null,
    val estado: String,
    val version: Long? = null,
)

@Serializable
data class EventoDto(
    val idEvento: String,
    val idGuia: String,
    val tipo: String,
    val fechaHora: String,
    val descripcion: String? = null,
    val latitud: Double? = null,
    val longitud: Double? = null,
    // JPEG en base64
    val foto: String? = null,
    // Version de la guia que conoce el telefono al enviar: si en el central es otra, la guia fue
    // modificada (politica de conflictos) y la respuesta trae los datos actuales
    val versionGuia: Long? = null,
)

@Serializable
data class RolDto(val rol: String, val nroEmpresa: Long? = null, val empresa: String? = null, val estado: String? = null)

@Serializable
data class UsuarioDto(val cedula: String, val nombre: String = "", val roles: List<RolDto> = emptyList())

@Serializable
data class TokenDto(val token: String, val expira: Long, val usuario: UsuarioDto)

@Serializable
private data class ResultadoEventoDto(
    // APLICADO, SIN_EFECTO o CONFLICTO
    val resultado: String? = null,
    val codigo: String? = null,
    val detalle: String? = null,
    val estadoGuia: String? = null,
    val version: Long? = null,
    val duplicado: Boolean = false,
    val guiaModificada: Boolean = false,
    // Datos actuales de la guia (null si ya no es de este chofer)
    val guia: ViajeDto? = null,
)

@Serializable
private data class ErrorDto(val error: String? = null, val detalle: String? = null, val campos: Map<String, String>? = null)

@Serializable
private data class CanjeDto(val code: String, val codeVerifier: String, val nonce: String, val redirectUri: String)

sealed interface ResultadoEnvio {
    // 200: APLICADO o SIN_EFECTO (el efecto ya estaba aplicado)
    data class Aceptado(
        val estadoGuia: String?,
        val version: Long?,
        val duplicado: Boolean,
        val sinEfecto: Boolean = false,
        val codigo: String? = null,
        val detalle: String? = null,
        val guiaModificada: Boolean = false,
        val guia: ViajeDto? = null,
    ) : ResultadoEnvio
    // 409 CONFLICTO: el central lo registro pero no lo pudo aplicar (no se reintenta)
    data class Conflicto(val codigo: String?, val detalle: String?, val guia: ViajeDto? = null) : ResultadoEnvio
    // 4xx de negocio o de datos: no se reintenta
    data class Rechazado(val codigo: String?, val detalle: String?) : ResultadoEnvio
    // 401: el token vencio o es invalido; hay que volver a ingresar
    data object SesionVencida : ResultadoEnvio
    // Sin red, timeout o 5xx: se reintenta con el mismo idEvento
    data class Transitorio(val motivo: String) : ResultadoEnvio
}

class SinConexionException(mensaje: String, causa: Throwable? = null) : IOException(mensaje, causa)
class SesionVencidaException : Exception("La sesion vencio")
class NoEsChoferException : Exception("No es chofer habilitado")
class ErrorServidorException(mensaje: String) : Exception(mensaje)
// El servidor rechazo el ingreso con un motivo (no_chofer, token_invalido, idp_no_disponible, ...)
class ErrorLoginException(val motivo: String) : Exception("Ingreso rechazado: $motivo")

interface ApiMovil {
    // Code de gub.uy (redirect loopback) + code_verifier y nonce del login: el servidor lo canjea con el
    // client secret y devuelve el token de la app
    suspend fun canjearCodigo(code: String, codeVerifier: String, nonce: String, redirectUri: String): TokenDto
    suspend fun viajes(token: String): List<ViajeDto>
    suspend fun enviarEvento(token: String, evento: EventoDto): ResultadoEnvio
}

class ApiMovilHttp(
    private val baseUrl: String,
    private val cliente: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) : ApiMovil {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val tipoJson = "application/json; charset=utf-8".toMediaType()

    override suspend fun canjearCodigo(
        code: String,
        codeVerifier: String,
        nonce: String,
        redirectUri: String,
    ): TokenDto = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/movil/auth/token")
            .header("X-Correlation-Id", UUID.randomUUID().toString())
            .post(json.encodeToString(CanjeDto(code, codeVerifier, nonce, redirectUri)).toRequestBody(tipoJson))
            .build()
        ejecutar(req) { codigo, cuerpo ->
            if (codigo == 200) return@ejecutar json.decodeFromString<TokenDto>(cuerpo)
            val motivo = runCatching { json.decodeFromString<ErrorDto>(cuerpo) }.getOrNull()?.error
            when {
                motivo != null -> throw ErrorLoginException(motivo)
                codigo >= 500 -> throw ErrorServidorException("El servidor respondió $codigo")
                else -> throw ErrorServidorException("El código de ingreso no es válido o venció. Volvé a intentarlo.")
            }
        }
    }

    override suspend fun viajes(token: String): List<ViajeDto> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/movil/viajes")
            .header("Authorization", "Bearer $token")
            .header("X-Correlation-Id", UUID.randomUUID().toString())
            .get()
            .build()
        ejecutar(req) { codigo, cuerpo ->
            when (codigo) {
                200 -> json.decodeFromString<List<ViajeDto>>(cuerpo)
                401 -> throw SesionVencidaException()
                403 -> throw NoEsChoferException()
                else -> throw ErrorServidorException("El servidor respondió $codigo")
            }
        }
    }

    override suspend fun enviarEvento(token: String, evento: EventoDto): ResultadoEnvio = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$baseUrl/api/movil/eventos")
            .header("Authorization", "Bearer $token")
            .header("X-Correlation-Id", evento.idEvento)
            .post(json.encodeToString(evento).toRequestBody(tipoJson))
            .build()
        try {
            ejecutar(req) { codigo, cuerpo ->
                when {
                    codigo == 200 -> json.decodeFromString<ResultadoEventoDto>(cuerpo).let {
                        ResultadoEnvio.Aceptado(
                            it.estadoGuia, it.version, it.duplicado, it.resultado == "SIN_EFECTO", it.codigo,
                            it.detalle, it.guiaModificada, it.guia,
                        )
                    }
                    codigo == 409 && conflicto(cuerpo) != null ->
                        conflicto(cuerpo)!!.let { ResultadoEnvio.Conflicto(it.codigo, it.detalle, it.guia) }
                    codigo == 401 -> ResultadoEnvio.SesionVencida
                    codigo in 400..499 && codigo != 408 && codigo != 429 -> {
                        val error = runCatching { json.decodeFromString<ErrorDto>(cuerpo) }.getOrNull()
                        ResultadoEnvio.Rechazado(error?.error, error?.campos?.values?.firstOrNull() ?: error?.detalle)
                    }
                    else -> ResultadoEnvio.Transitorio("El servidor respondió $codigo")
                }
            }
        } catch (e: SinConexionException) {
            ResultadoEnvio.Transitorio(e.message ?: "Sin conexión")
        }
    }

    // 409 con resultado CONFLICTO (evento registrado sin aplicar); otro 409 es un rechazo comun
    private fun conflicto(cuerpo: String): ResultadoEventoDto? =
        runCatching { json.decodeFromString<ResultadoEventoDto>(cuerpo) }.getOrNull()?.takeIf { it.resultado == "CONFLICTO" }

    // Deja el log de cada llamada. La ruta va sin la query: el token nunca aparece en el log.
    private fun <T> ejecutar(req: Request, procesar: (Int, String) -> T): T {
        val correlationId = req.header("X-Correlation-Id")
        val datos = mapOf("metodo" to req.method, "ruta" to req.url.encodedPath)
        val inicio = System.nanoTime()
        val (codigo, cuerpo) = try {
            cliente.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: IOException) {
            LogMovil.registrar(
                "http.request", LogMovil.Nivel.WARN, correlationId,
                roundtripSeconds = segundosDesde(inicio), error = e.toString(), datos = datos,
            )
            throw SinConexionException("No se pudo conectar con el servidor", e)
        }
        LogMovil.registrar(
            "http.request", if (codigo >= 500) LogMovil.Nivel.ERROR else LogMovil.Nivel.INFO, correlationId,
            responseCode = codigo, roundtripSeconds = segundosDesde(inicio), datos = datos,
        )
        return procesar(codigo, cuerpo)
    }

    private fun segundosDesde(inicioNanos: Long) = (System.nanoTime() - inicioNanos) / 1_000_000_000.0
}
