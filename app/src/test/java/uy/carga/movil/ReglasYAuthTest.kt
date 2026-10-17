package uy.carga.movil

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uy.carga.movil.dominio.EstadoGuia
import uy.carga.movil.dominio.ReglasViaje
import uy.carga.movil.dominio.TipoEvento
import uy.carga.movil.sesion.ConfigGubUy
import uy.carga.movil.sesion.ReceptorLoopback
import uy.carga.movil.sesion.RepositorioAuth
import uy.carga.movil.sesion.ResultadoLogin

class ReglasYAuthTest {

    @Test
    fun estadoEfectivoAplicaLosPendientesEnOrden() {
        assertEquals("ASIGNADA", ReglasViaje.estadoEfectivo("ASIGNADA", emptyList()))
        assertEquals("EN_CURSO", ReglasViaje.estadoEfectivo("ASIGNADA", listOf(TipoEvento.INICIO, TipoEvento.CARGA_PARCIAL)))
        assertEquals("FINALIZADA", ReglasViaje.estadoEfectivo("ASIGNADA", listOf(TipoEvento.INICIO, TipoEvento.FIN)))
        // un INICIO pendiente que el central ya aplico (respuesta perdida) no cambia nada
        assertEquals("EN_CURSO", ReglasViaje.estadoEfectivo("EN_CURSO", listOf(TipoEvento.INICIO)))
    }

    @Test
    fun accionesSegunEstado() {
        assertEquals(listOf(TipoEvento.INICIO), ReglasViaje.accionesPermitidas(EstadoGuia.ASIGNADA))
        assertEquals(
            listOf(TipoEvento.CARGA_PARCIAL, TipoEvento.DESCARGA_PARCIAL, TipoEvento.INCIDENTE, TipoEvento.FIN),
            ReglasViaje.accionesPermitidas(EstadoGuia.EN_CURSO),
        )
        assertTrue(ReglasViaje.accionesPermitidas(EstadoGuia.FINALIZADA).isEmpty())
    }

    @Test
    fun pkceS256ComoElEjemploDelRfc7636() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            RepositorioAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    private val gubUy = ConfigGubUy(
        authorizeUrl = "https://auth-testing.iduruguay.gub.uy/oidc/v1/authorize",
        clientId = "890192",
        scopes = "openid personal_info email document",
        redirectUri = "http://localhost:8080",
    )

    @Test
    fun loginVaDirectoAGubUyYElServidorCanjeaElCodigo() = runTest {
        val sesion = SesionFalsa(token = null)
        val central = CentralFalso()
        var borrados = 0
        var ingresos = 0
        val auth = RepositorioAuth(gubUy, central, sesion, { borrados++ }, { ingresos++ })

        val url = auth.urlLogin()
        val pendiente = sesion.loginPendiente
        assertNotNull(pendiente)
        assertTrue(url.startsWith("https://auth-testing.iduruguay.gub.uy/oidc/v1/authorize?response_type=code&client_id=890192"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%3A8080"))
        assertTrue(url.contains("scope=openid%20personal_info%20email%20document"))
        assertTrue(url.contains("state=${pendiente!!.state}"))
        assertTrue(url.contains("nonce=${pendiente.nonce}"))
        assertTrue(url.contains("code_challenge=${RepositorioAuth.challenge(pendiente.verifier)}&code_challenge_method=S256"))
        assertTrue(url.endsWith("&prompt=login"))

        val r = auth.completarLogin("codigo", pendiente.state, null)
        assertEquals(ResultadoLogin.Ok("Bryan"), r)
        assertEquals(listOf("codigo", pendiente.verifier, pendiente.nonce, "http://localhost:8080"), central.ultimoCanje)
        assertEquals("token-ok", sesion.sesion.value?.token)
        assertNull(sesion.loginPendiente)
        assertEquals(1, ingresos)
        assertEquals(0, borrados)
    }

    @Test
    fun stateDistintoNoCanjea() = runTest {
        val sesion = SesionFalsa(token = null)
        val central = CentralFalso()
        val auth = RepositorioAuth(gubUy, central, sesion, {}, {})
        auth.urlLogin()
        val r = auth.completarLogin("codigo", "otro-state", null) as ResultadoLogin.Error
        assertTrue(r.mensaje.contains("venció"))
        assertNull(central.ultimoCanje)
        assertNull(sesion.sesion.value)
    }

    @Test
    fun cancelarEnGubUy() = runTest {
        val auth = RepositorioAuth(gubUy, CentralFalso(), SesionFalsa(token = null), {}, {})
        auth.urlLogin()
        val r = auth.completarLogin(null, null, "access_denied") as ResultadoLogin.Error
        assertEquals(RepositorioAuth.MENSAJES["cancelado"], r.mensaje)
    }

    @Test
    fun elServidorRechazaPorqueNoEsChofer() = runTest {
        val sesion = SesionFalsa(token = null)
        val central = CentralFalso().apply { rechazoLogin = "no_chofer" }
        val auth = RepositorioAuth(gubUy, central, sesion, {}, {})
        auth.urlLogin()
        val r = auth.completarLogin("codigo", sesion.loginPendiente!!.state, null) as ResultadoLogin.Error
        assertTrue(r.mensaje.contains("chofer"))
        assertNull(sesion.sesion.value)
    }

    @Test
    fun parametrosDeLaVueltaLoopback() {
        assertEquals(
            mapOf("code" to "a b/c", "state" to "xyz"),
            ReceptorLoopback.parametros("code=a+b%2Fc&state=xyz"),
        )
        assertEquals(8080, ReceptorLoopback.puertoDe("http://localhost:8080"))
        assertEquals(80, ReceptorLoopback.puertoDe("http://localhost"))
    }
}
