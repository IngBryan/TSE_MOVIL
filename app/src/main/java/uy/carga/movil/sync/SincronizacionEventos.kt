package uy.carga.movil.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import uy.carga.movil.CargaApp
import uy.carga.movil.datos.ProgramadorSync
import uy.carga.movil.datos.ResultadoSync
import java.util.concurrent.TimeUnit

// Envia los eventos pendientes cuando hay red. Si falla por la red o el servidor, WorkManager lo
// reintenta con backoff exponencial; los eventos rechazados por el central no se reintentan.
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repositorio = (applicationContext as CargaApp).contenedor.viajes
        return when (repositorio.sincronizarPendientes()) {
            ResultadoSync.COMPLETO -> Result.success()
            ResultadoSync.REINTENTAR -> Result.retry()
            // Sin token valido no tiene sentido reintentar: al volver a ingresar se agenda de nuevo
            ResultadoSync.SESION_VENCIDA -> Result.success()
        }
    }
}

class ProgramadorWorkManager(private val context: Context) : ProgramadorSync {
    // urgente = true reemplaza un envio que este esperando su backoff (por ejemplo al volver la red
    // o al tocar "Sincronizar"); si no, se mantiene el agendado, que igual envia todos los pendientes.
    override fun programar(urgente: Boolean) {
        val trabajo = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            NOMBRE,
            if (urgente) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            trabajo,
        )
    }

    companion object {
        const val NOMBRE = "sincronizar-eventos"
    }
}
