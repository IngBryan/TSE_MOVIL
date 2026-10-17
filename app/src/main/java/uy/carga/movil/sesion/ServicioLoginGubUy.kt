package uy.carga.movil.sesion

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import uy.carga.movil.CargaApp
import uy.carga.movil.R

// Mientras el chofer ingresa en gub.uy (Custom Tab de Chrome) la app queda en segundo plano y Android
// puede matar su proceso por falta de memoria; con el proceso se iria el ReceptorLoopback y gub.uy
// volveria a http://localhost:8080 sin nadie escuchando (ERR_CONNECTION_REFUSED). Este servicio en
// primer plano (shortService, con una notificacion "Ingresando con gub.uy") mantiene vivo el proceso
// hasta que llega la vuelta o vence la espera.
class ServicioLoginGubUy : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notificaciones = getSystemService(NotificationManager::class.java)
        notificaciones.createNotificationChannel(
            NotificationChannel(CANAL, "Ingreso con gub.uy", NotificationManager.IMPORTANCE_LOW),
        )
        val notificacion = NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("carga.uy")
            .setContentText("Ingresando con gub.uy…")
            .setOngoing(true)
            .setSilent(true)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICACION, notificacion,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE else 0,
        )
        val receptor = (application as CargaApp).contenedor.receptor
        if (!receptor.avisarAlTerminar { stopSelf() }) stopSelf()
        return START_NOT_STICKY
    }

    // shortService (Android 14+): el sistema da unos 3 minutos; si el login no termino, se corta
    override fun onTimeout(startId: Int) {
        (application as CargaApp).contenedor.receptor.detener()
        stopSelf()
    }

    companion object {
        private const val CANAL = "login_gubuy"
        private const val NOTIFICACION = 1

        fun iniciar(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ServicioLoginGubUy::class.java))
        }
    }
}
