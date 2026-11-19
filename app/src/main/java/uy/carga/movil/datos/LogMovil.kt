package uy.carga.movil.datos

import android.util.Log
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale

// Logs de la app: una linea JSON con el mismo formato que LogEstructurado del componente central, para seguir
// una operacion por su correlationId entre el telefono y el servidor (se ven con adb logcat -s carga.uy). Lo
// propio de cada operacion va en datos. Nunca se loguean el token, la foto ni la ubicacion.
object LogMovil {

    private const val COMPONENTE = "app-movil"
    private const val TAG = "carga.uy"

    enum class Nivel { INFO, WARN, ERROR }

    // Se reemplaza en los tests
    var salida: (Nivel, String) -> Unit = { nivel, linea ->
        when (nivel) {
            Nivel.INFO -> Log.i(TAG, linea)
            Nivel.WARN -> Log.w(TAG, linea)
            Nivel.ERROR -> Log.e(TAG, linea)
        }
    }

    fun registrar(
        evento: String,
        nivel: Nivel = Nivel.INFO,
        correlationId: String? = null,
        usuario: String? = null,
        responseCode: Int? = null,
        roundtripSeconds: Double? = null,
        error: String? = null,
        datos: Map<String, Any?> = emptyMap(),
    ) {
        val json = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("componente", COMPONENTE)
            put("evento", evento)
            correlationId?.let { put("correlationId", it) }
            usuario?.let { put("usuario", it) }
            responseCode?.let { put("responseCode", it) }
            roundtripSeconds?.let { put("roundtripSeconds", String.format(Locale.ROOT, "%.3f", it).toDouble()) }
            error?.let { put("error", it) }
            val propios = aJson(datos)
            if (propios.isNotEmpty()) put("datos", propios)
        }
        salida(nivel, json.toString())
    }

    private fun aJson(datos: Map<String, Any?>) = JsonObject(
        datos.filterValues { it != null }.mapValues { (_, valor) ->
            when (valor) {
                is Number -> JsonPrimitive(valor)
                is Boolean -> JsonPrimitive(valor)
                null -> JsonNull
                else -> JsonPrimitive(valor.toString())
            }
        },
    )
}
