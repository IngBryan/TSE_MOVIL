package uy.carga.movil.datos.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// Guia descargada del central. estadoServidor es el ultimo estado confirmado por el central; el
// estado que se muestra se calcula aplicando los eventos pendientes (ReglasViaje.estadoEfectivo).
@Entity(tableName = "viajes")
data class ViajeEntity(
    @PrimaryKey val id: String,
    val nroEmpresa: Long?,
    val empresa: String?,
    val rubroCliente: String,
    val tipoCarga: String?,
    val pesoCargaKg: Long,
    val fecha: String,
    val origen: String,
    val destino: String,
    val matricula: String?,
    val vehiculo: String?,
    val estadoServidor: String,
    val version: Long?,
    // La empresa modifico datos de la guia desde la ultima vez que el chofer la vio (politica de conflictos)
    @ColumnInfo(defaultValue = "0") val avisoModificada: Boolean = false,
)

// Evento registrado en el telefono. idEvento es el UUID que identifica el evento en el central
// (los reenvios usan el mismo, asi el central no lo aplica dos veces).
@Entity(tableName = "eventos", indices = [Index("idGuia")])
data class EventoEntity(
    @PrimaryKey val idEvento: String,
    val idGuia: String,
    val tipo: String,
    // Hora del dispositivo, ISO-8601 con zona
    val fechaHora: String,
    // Orden de registro (epoch ms): los pendientes se envian en este orden
    val creado: Long,
    val descripcion: String?,
    val latitud: Double?,
    val longitud: Double?,
    // Foto comprimida guardada en el almacenamiento interno hasta que se sincroniza
    val rutaFoto: String?,
    val estado: String,
    val motivo: String? = null,
    val intentos: Int = 0,
)

@Dao
interface ViajeDao {
    @Query("SELECT * FROM viajes ORDER BY fecha, id")
    fun observar(): Flow<List<ViajeEntity>>

    @Query("SELECT * FROM viajes")
    suspend fun todos(): List<ViajeEntity>

    @Query("SELECT * FROM viajes WHERE id = :id")
    suspend fun buscar(id: String): ViajeEntity?

    @Upsert
    suspend fun guardar(viajes: List<ViajeEntity>)

    @Query("UPDATE viajes SET estadoServidor = :estado, version = :version WHERE id = :id")
    suspend fun actualizarEstado(id: String, estado: String, version: Long?)

    @Query("UPDATE viajes SET avisoModificada = :aviso WHERE id = :id")
    suspend fun marcarAviso(id: String, aviso: Boolean)

    @Query("DELETE FROM viajes WHERE id IN (:ids)")
    suspend fun borrar(ids: List<String>)

    @Query("DELETE FROM viajes")
    suspend fun borrarTodo()
}

@Dao
interface EventoDao {
    @Query("SELECT * FROM eventos ORDER BY creado")
    fun observar(): Flow<List<EventoEntity>>

    @Query("SELECT * FROM eventos WHERE idGuia = :idGuia ORDER BY creado")
    suspend fun deGuia(idGuia: String): List<EventoEntity>

    @Query("SELECT * FROM eventos WHERE estado = 'PENDIENTE' ORDER BY creado LIMIT 1")
    suspend fun primerPendiente(): EventoEntity?

    @Query("SELECT COUNT(*) FROM eventos WHERE estado = 'PENDIENTE'")
    suspend fun cantidadPendientes(): Int

    @Insert
    suspend fun insertar(evento: EventoEntity)

    @Query("UPDATE eventos SET estado = :estado, motivo = :motivo, rutaFoto = :rutaFoto WHERE idEvento = :idEvento")
    suspend fun marcar(idEvento: String, estado: String, motivo: String?, rutaFoto: String?)

    @Query("UPDATE eventos SET intentos = intentos + 1, motivo = :motivo WHERE idEvento = :idEvento")
    suspend fun registrarIntento(idEvento: String, motivo: String?)

    @Query("DELETE FROM eventos WHERE idGuia IN (:idsGuia)")
    suspend fun borrarDeGuias(idsGuia: List<String>)

    @Query("DELETE FROM eventos")
    suspend fun borrarTodo()
}

@Database(entities = [ViajeEntity::class, EventoEntity::class], version = 3, exportSchema = false)
abstract class BaseLocal : RoomDatabase() {
    abstract fun viajes(): ViajeDao
    abstract fun eventos(): EventoDao
}

// v2: tipo de carga de la guia. Migracion explicita para no perder eventos pendientes al actualizar la app.
val MIGRACION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE viajes ADD COLUMN tipoCarga TEXT")
    }
}

// v3: aviso de guia modificada por la empresa (politica de resolucion de conflictos).
val MIGRACION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE viajes ADD COLUMN avisoModificada INTEGER NOT NULL DEFAULT 0")
    }
}
