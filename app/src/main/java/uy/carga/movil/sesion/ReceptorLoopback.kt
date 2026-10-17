package uy.carga.movil.sesion

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

// Lo que gub.uy manda a la redirect loopback: ?code=...&state=... o ?error=...
data class VueltaGubUy(val code: String?, val state: String?, val error: String?)

// Servidor HTTP minimo en 127.0.0.1:<puerto> del telefono que recibe la vuelta del login con gub.uy
// (redirect URI http://localhost:8080, RFC 8252 seccion 7.3). Solo escucha mientras hay un login en curso:
// atiende el primer request con code o error, responde una pagina que vuelve a la app (cargauy://auth,
// sin datos) y se cierra. El code no sale del proceso de la app y sin el code_verifier no sirve.
class ReceptorLoopback(private val puerto: Int) {

    private val canal = Channel<VueltaGubUy>(Channel.CONFLATED)
    val vueltas: Flow<VueltaGubUy> = canal.receiveAsFlow()

    private var servidor: ServerSocket? = null

    private var alTerminar: (() -> Unit)? = null

    // false si no se pudo abrir el puerto (otra app lo esta usando)
    @Synchronized
    fun escuchar(): Boolean {
        servidor?.takeIf { !it.isClosed }?.let { return true }
        val s = try {
            ServerSocket(puerto, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = ESPERA_MAXIMA_MS }
        } catch (e: IOException) {
            return false
        }
        servidor = s
        thread(name = "login-gubuy-loopback", isDaemon = true) {
            try {
                // Cada conexion en su hilo: Chrome abre conexiones por adelantado que no mandan nada, y
                // no pueden bloquear ni cerrar el servidor mientras llega la de la vuelta.
                while (!s.isClosed) {
                    val socket = s.accept()
                    thread(name = "login-gubuy-conexion", isDaemon = true) { atenderConexion(s, socket) }
                }
            } catch (_: IOException) {
                // Vencio la espera (el chofer no termino el login) o se cerro el socket
            } finally {
                s.close()
                synchronized(this) { alTerminar.also { alTerminar = null } }?.invoke()
            }
        }
        return true
    }

    // Registra que hacer cuando el servidor se cierre (llego la vuelta, vencio la espera o detener()).
    // false si ya no esta escuchando: no se va a llamar.
    @Synchronized
    fun avisarAlTerminar(accion: () -> Unit): Boolean {
        if (servidor?.isClosed != false) return false
        alTerminar = accion
        return true
    }

    @Synchronized
    fun detener() {
        servidor?.close()
    }

    private fun atenderConexion(servidor: ServerSocket, socket: Socket) {
        try {
            socket.use { atender(it) }?.let {
                canal.trySend(it)
                servidor.close()
            }
        } catch (_: IOException) {
            // Conexion que no mando nada o se corto: se ignora, el servidor sigue esperando
        }
    }

    private fun atender(socket: Socket): VueltaGubUy? {
        socket.soTimeout = 5_000
        val entrada = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        val linea = entrada.readLine() ?: return null
        // Se leen los headers hasta la linea vacia para que el navegador no reciba un reset
        while (!entrada.readLine().isNullOrEmpty()) Unit

        val (metodo, ruta) = linea.split(" ").let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
        val params = parametros(ruta.substringAfter('?', ""))
        val vuelta = if (metodo == "GET" && ruta.substringBefore('?') == "/" && ("code" in params || "error" in params)) {
            VueltaGubUy(params["code"], params["state"], params["error"])
        } else {
            null
        }
        responder(socket, vuelta)
        return vuelta
    }

    private fun responder(socket: Socket, vuelta: VueltaGubUy?) {
        val (estado, html) = if (vuelta == null) {
            "404 Not Found" to "<!doctype html><title>carga.uy</title>"
        } else {
            val texto = if (vuelta.code != null) "Listo, ya podés volver a la app carga.uy." else "No se pudo completar el ingreso."
            "200 OK" to """<!doctype html>
<html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>carga.uy</title>
<style>body{font-family:system-ui,sans-serif;display:flex;min-height:90vh;align-items:center;justify-content:center;text-align:center;padding:16px}
a{display:inline-block;margin-top:16px;padding:12px 24px;border-radius:999px;background:#0b5394;color:#fff;text-decoration:none}</style>
</head><body><main><p>$texto</p><a id="volver" href="$VOLVER">Volver a la app</a></main>
<script>location.replace("$VOLVER")</script></body></html>"""
        }
        val cuerpo = html.toByteArray(Charsets.UTF_8)
        val cabecera = "HTTP/1.1 $estado\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${cuerpo.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        socket.getOutputStream().apply {
            write(cabecera.toByteArray(Charsets.US_ASCII))
            write(cuerpo)
            flush()
        }
    }

    companion object {
        // Abre MainActivity (singleTask) y cierra el Custom Tab; no lleva datos
        const val VOLVER = "cargauy://auth"
        private const val ESPERA_MAXIMA_MS = 10 * 60 * 1000

        fun parametros(query: String): Map<String, String> =
            query.split('&').filter { it.isNotEmpty() }.associate {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
            }

        fun puertoDe(redirectUri: String): Int = java.net.URI(redirectUri).port.takeIf { it > 0 } ?: 80
    }
}
