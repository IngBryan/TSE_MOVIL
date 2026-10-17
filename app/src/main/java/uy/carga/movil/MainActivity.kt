package uy.carga.movil

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import uy.carga.movil.ui.App
import uy.carga.movil.ui.AppViewModel

class MainActivity : ComponentActivity() {

    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val oscuro = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (oscuro) darkColorScheme(primary = Color(0xFF9CC3F0))
                else lightColorScheme(primary = Color(0xFF0B5394)),
            ) {
                App(vm)
            }
        }
        if (savedInstanceState == null) procesarVuelta(intent)
    }

    // launchMode singleTask: la vuelta del Custom Tab llega aca y cierra el Custom Tab.
    // - uy.carga.movil:/oauth2redirect?code=...&state=... : redirect URI propia registrada en ID Uruguay.
    // - cargauy://auth : la pagina del ReceptorLoopback (redirect http://localhost:8080) solo trae la app
    //   al frente; el code ya lo recibio el ReceptorLoopback.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        procesarVuelta(intent)
    }

    private fun procesarVuelta(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.toString().startsWith(BuildConfig.OIDC_REDIRECT) && uri.scheme == SCHEME_APP) {
            vm.vueltaGubUy(uri.getQueryParameter("code"), uri.getQueryParameter("state"), uri.getQueryParameter("error"))
        }
        setIntent(Intent(this, MainActivity::class.java)) // que una rotacion no lo procese de nuevo
    }

    companion object {
        const val SCHEME_APP = "uy.carga.movil"
    }
}
