package uy.carga.movil.datos

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import uy.carga.movil.datos.local.EventoDao
import uy.carga.movil.datos.local.EventoEntity
import uy.carga.movil.datos.local.ViajeDao
import uy.carga.movil.datos.local.ViajeEntity
import uy.carga.movil.datos.remoto.ApiMovil
import uy.carga.movil.datos.remoto.EventoDto
import uy.carga.movil.datos.remoto.NoEsChoferException
import uy.carga.movil.datos.remoto.ResultadoEnvio
import uy.carga.movil.datos.remoto.SesionVencidaException
import uy.carga.movil.datos.remoto.SinConexionException
import uy.carga.movil.datos.remoto.ViajeDto
import uy.carga.movil.dominio.EstadoEvento
import uy.carga.movil.dominio.ReglasViaje
import uy.carga.movil.dominio.TipoEvento
import uy.carga.movil.dominio.Viaje
import uy.carga.movil.sesion.AlmacenSesion
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID

sealed interface ResultadoRefresco {
    data object Ok : ResultadoRefresco
    data object SinConexion : ResultadoRefresco
    data object SesionVencida : ResultadoRefresco
    data object NoEsChofer : ResultadoRefresco
    data class Error(val mensaje: String) : ResultadoRefresco
}

enum class ResultadoSync { COMPLETO, REINTENTAR, SESION_VENCIDA }

class AccionNoPermitidaException(mensaje: String) : Exception(mensaje)

// Fotos comprimidas de los eventos, guardadas hasta que el evento se sincroniza.
interface AlmacenFotos {
    fun guardar(idEvento: String, jpeg: ByteArray): String
    fun leer(ruta: String): ByteArray?
    fun borrar(ruta: String)
}

// Encola el envio de los eventos pendientes (WorkManager con red disponible).
fun interface ProgramadorSync {
    fun programar(urgente: Boolean)
}

data class Ubicacion(val latitud: Double, val longitud: Double)

// Fuente de verdad de la app: los viajes y eventos en Room. La pantalla siempre lee de Room; el
// central se consulta para refrescar y los eventos se registran primero localmente (PENDIENTE) y
// despues los envia el sincronizador, en orden y con su idEvento (el central es idempotente).
class RepositorioViajes(
    private val viajes: ViajeDao,
    private val eventos: EventoDao,
    private val api: ApiMovil,
    private val sesion: AlmacenSesion,
    private val fotos: AlmacenFotos,
    private val programador: ProgramadorSync,
    private val ahora: () -> Long = System::currentTimeMillis,
    private val ahoraConZona: () -> OffsetDateTime = OffsetDateTime::now,
    private val nuevoId: () -> String = { UUID.randomUUID().toString() },
) {

    fun observarViajes(): Flow<List<Viaje>> =
        combine(viajes.observar(), eventos.observar()) { vs, es ->
            val porGuia = es.groupBy { it.idGuia }
            vs.map { aViaje(it, porGuia[it.id].orEmpty()) }
        }

    fun observarViaje(id: String): Flow<Pair<Viaje?, List<EventoEntity>>> =
        combine(viajes.observar(), eventos.observar()) { vs, es ->
            val propios = es.filter { it.idGuia == id }
            vs.firstOrNull { it.id == id }?.let { aViaje(it, propios) } to propios
        }

    suspend fun refrescar(): ResultadoRefresco {
        val token = sesion.sesion.value?.token ?: return ResultadoRefresco.SesionVencida
        val remotos: List<ViajeDto> = try {
            api.viajes(token)
        } catch (e: SinConexionException) {
            return ResultadoRefresco.SinConexion
        } catch (e: SesionVencidaException) {
            return ResultadoRefresco.SesionVencida
        } catch (e: NoEsChoferException) {
            return ResultadoRefresco.NoEsChofer
        } catch (e: Exception) {
            return ResultadoRefresco.Error(e.message ?: "Error al consultar los viajes")
        }

        // Politica de conflictos: los datos de la guia son de la empresa, gana siempre el central. Si
        // cambiaron respecto de lo que el chofer tenia, se marca el aviso "La empresa modifico la guia".
        val locales = viajes.todos().associateBy { it.id }
        viajes.guardar(remotos.map { r -> r.aEntidad(locales[r.id]) })
        // Los que el central ya no devuelve (finalizados o reasignados) se borran, salvo que tengan
        // eventos sin enviar: esos se conservan hasta sincronizarlos.
        val idsRemotos = remotos.map { it.id }.toSet()
        val sobrantes = viajes.todos().map { it.id }.filter { it !in idsRemotos }
            .filter { id -> eventos.deGuia(id).none { it.estado == EstadoEvento.PENDIENTE.name } }
        if (sobrantes.isNotEmpty()) {
            eventos.borrarDeGuias(sobrantes)
            viajes.borrar(sobrantes)
        }
        sesion.ultimaActualizacion = ahora()
        return ResultadoRefresco.Ok
    }

    // Registra el evento en el telefono (PENDIENTE), actualiza el estado local y agenda el envio.
    suspend fun registrarEvento(
        idGuia: String,
        tipo: TipoEvento,
        descripcion: String? = null,
        fotoJpeg: ByteArray? = null,
        ubicacion: Ubicacion? = null,
    ): String {
        val viaje = viajes.buscar(idGuia) ?: throw AccionNoPermitidaException("El viaje no está en el teléfono.")
        val estado = estadoEfectivo(viaje, eventos.deGuia(idGuia))
        if (tipo !in ReglasViaje.accionesPermitidas(estado)) {
            throw AccionNoPermitidaException("No se puede registrar ${tipo.etiqueta.lowercase()} en un viaje ${estadoTexto(estado)}.")
        }
        val texto = descripcion?.trim()?.takeIf { it.isNotEmpty() }
        if (tipo == TipoEvento.INCIDENTE && texto == null) {
            throw AccionNoPermitidaException("Describí el incidente.")
        }
        val id = nuevoId()
        val ruta = fotoJpeg?.let { fotos.guardar(id, it) }
        eventos.insertar(
            EventoEntity(
                idEvento = id,
                idGuia = idGuia,
                tipo = tipo.name,
                fechaHora = ahoraConZona().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                creado = ahora(),
                descripcion = texto,
                latitud = ubicacion?.latitud,
                longitud = ubicacion?.longitud,
                rutaFoto = ruta,
                estado = EstadoEvento.PENDIENTE.name,
            ),
        )
        LogMovil.registrar(
            "evento.registrado", correlationId = id, usuario = sesion.sesion.value?.cedula,
            datos = mapOf("guia" to idGuia, "tipo" to tipo.name, "foto" to (ruta != null)),
        )
        programador.programar(urgente = false)
        return id
    }

    // Envia los pendientes de a uno, en el orden en que se registraron. Si uno falla por la red se
    // corta (para no enviar un FIN antes que su INICIO) y se reintenta despues con el mismo idEvento.
    suspend fun sincronizarPendientes(): ResultadoSync {
        val token = sesion.sesion.value?.token ?: return ResultadoSync.SESION_VENCIDA
        while (true) {
            val evento = eventos.primerPendiente() ?: return ResultadoSync.COMPLETO
            val foto = evento.rutaFoto?.let { fotos.leer(it) }
            val dto = EventoDto(
                idEvento = evento.idEvento,
                idGuia = evento.idGuia,
                tipo = evento.tipo,
                fechaHora = evento.fechaHora,
                descripcion = evento.descripcion,
                latitud = evento.latitud,
                longitud = evento.longitud,
                foto = foto?.let { Base64.getEncoder().encodeToString(it) },
                // la version que el telefono conoce ahora (actualizada por los envios anteriores)
                versionGuia = viajes.buscar(evento.idGuia)?.version,
            )
            val r = api.enviarEvento(token, dto)
            logEnvio(evento, r)
            when (r) {
                is ResultadoEnvio.Aceptado -> {
                    val nota = if (r.sinEfecto) ReglasViaje.mensajeSinEfecto(r.codigo, r.detalle) else null
                    eventos.marcar(evento.idEvento, EstadoEvento.SINCRONIZADO.name, nota, null)
                    if (r.guia != null) {
                        actualizarConDatosDelCentral(r.guia)
                    } else if (r.estadoGuia != null) {
                        viajes.actualizarEstado(evento.idGuia, r.estadoGuia, r.version)
                    }
                    evento.rutaFoto?.let { fotos.borrar(it) }
                }
                is ResultadoEnvio.Conflicto -> {
                    eventos.marcar(
                        evento.idEvento, EstadoEvento.CONFLICTO.name,
                        ReglasViaje.mensajeRechazo(r.codigo, r.detalle), null,
                    )
                    r.guia?.let { actualizarConDatosDelCentral(it) }
                    evento.rutaFoto?.let { fotos.borrar(it) }
                }
                is ResultadoEnvio.Rechazado -> {
                    eventos.marcar(
                        evento.idEvento, EstadoEvento.RECHAZADO.name,
                        ReglasViaje.mensajeRechazo(r.codigo, r.detalle), null,
                    )
                    evento.rutaFoto?.let { fotos.borrar(it) }
                }
                ResultadoEnvio.SesionVencida -> return ResultadoSync.SESION_VENCIDA
                is ResultadoEnvio.Transitorio -> {
                    eventos.registrarIntento(evento.idEvento, r.motivo)
                    return ResultadoSync.REINTENTAR
                }
            }
        }
    }

    // correlationId = idEvento, el mismo en cada reintento: en el central se ve como un unico evento
    private fun logEnvio(evento: EventoEntity, r: ResultadoEnvio) {
        val (resultado, nivel) = when (r) {
            is ResultadoEnvio.Aceptado -> (if (r.duplicado) "duplicado" else "aceptado") to LogMovil.Nivel.INFO
            is ResultadoEnvio.Conflicto -> "conflicto" to LogMovil.Nivel.WARN
            is ResultadoEnvio.Rechazado -> "rechazado" to LogMovil.Nivel.WARN
            ResultadoEnvio.SesionVencida -> "sesion_vencida" to LogMovil.Nivel.WARN
            is ResultadoEnvio.Transitorio -> "reintentar" to LogMovil.Nivel.WARN
        }
        LogMovil.registrar(
            "evento.sincronizacion", nivel, evento.idEvento, sesion.sesion.value?.cedula,
            datos = mapOf(
                "guia" to evento.idGuia, "tipo" to evento.tipo, "resultado" to resultado, "intentos" to evento.intentos,
            ),
        )
    }

    // El central devolvio la guia actual (viene cuando la version que mando el telefono no coincidia,
    // guiaModificada=true, y tambien en cada envio): se guarda porque gana el central y, si la empresa
    // cambio datos, queda el aviso para el chofer (ver aEntidad).
    private suspend fun actualizarConDatosDelCentral(guia: ViajeDto) {
        viajes.guardar(listOf(guia.aEntidad(viajes.buscar(guia.id))))
    }

    suspend fun descartarAviso(idGuia: String) = viajes.marcarAviso(idGuia, false)

    suspend fun cantidadPendientes(): Int = eventos.cantidadPendientes()

    // Logout o cambio de usuario: se borran los datos del telefono
    suspend fun borrarDatosLocales() {
        eventos.borrarTodo()
        viajes.borrarTodo()
        sesion.ultimaActualizacion = null
    }

    private fun estadoEfectivo(v: ViajeEntity, propios: List<EventoEntity>): String =
        ReglasViaje.estadoEfectivo(
            v.estadoServidor,
            propios.filter { it.estado == EstadoEvento.PENDIENTE.name }.sortedBy { it.creado }
                .mapNotNull { runCatching { TipoEvento.valueOf(it.tipo) }.getOrNull() },
        )

    private fun aViaje(v: ViajeEntity, propios: List<EventoEntity>) = Viaje(
        id = v.id,
        nroEmpresa = v.nroEmpresa,
        empresa = v.empresa,
        rubroCliente = v.rubroCliente,
        tipoCarga = v.tipoCarga,
        pesoCargaKg = v.pesoCargaKg,
        fecha = v.fecha,
        origen = v.origen,
        destino = v.destino,
        matricula = v.matricula,
        vehiculo = v.vehiculo,
        estado = estadoEfectivo(v, propios),
        estadoServidor = v.estadoServidor,
        version = v.version,
        eventosPendientes = propios.count { it.estado == EstadoEvento.PENDIENTE.name },
        eventosRechazados = propios.count {
            it.estado == EstadoEvento.RECHAZADO.name || it.estado == EstadoEvento.CONFLICTO.name
        },
        avisoModificada = v.avisoModificada,
    )

    private fun estadoTexto(estado: String) = uy.carga.movil.dominio.EstadoGuia.etiqueta(estado).lowercase()

    // Convierte la guia del central; si ya estaba en el telefono y cambio algun dato de la empresa
    // (no el estado, que lo mueve el chofer), queda el aviso de guia modificada.
    private fun ViajeDto.aEntidad(local: ViajeEntity? = null): ViajeEntity {
        val nueva = ViajeEntity(
            id = id, nroEmpresa = nroEmpresa, empresa = empresa, rubroCliente = rubroCliente, tipoCarga = tipoCarga,
            pesoCargaKg = pesoCargaKg,
            fecha = fecha, origen = origen, destino = destino, matricula = matricula, vehiculo = vehiculo,
            estadoServidor = estado, version = version,
        )
        return nueva.copy(avisoModificada = local != null && (local.avisoModificada || datosDistintos(local, nueva)))
    }

    private fun datosDistintos(a: ViajeEntity, b: ViajeEntity) =
        a.copy(estadoServidor = "", version = null, avisoModificada = false) !=
            b.copy(estadoServidor = "", version = null, avisoModificada = false)
}
