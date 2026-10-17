package uy.carga.movil.dominio

// Estados de la guia en el central (los que llegan a la app son ASIGNADA y EN_CURSO; FINALIZADA
// solo existe localmente hasta que el FIN se sincroniza y la guia deja de venir del servidor).
object EstadoGuia {
    const val ASIGNADA = "ASIGNADA"
    const val EN_CURSO = "EN_CURSO"
    const val FINALIZADA = "FINALIZADA"
    const val CANCELADA = "CANCELADA"

    fun etiqueta(estado: String) = when (estado) {
        ASIGNADA -> "Asignado"
        EN_CURSO -> "En curso"
        FINALIZADA -> "Finalizado"
        CANCELADA -> "Cancelado"
        else -> estado
    }
}

enum class TipoEvento(val etiqueta: String) {
    INICIO("Iniciar viaje"),
    CARGA_PARCIAL("Carga parcial"),
    DESCARGA_PARCIAL("Descarga parcial"),
    INCIDENTE("Incidente"),
    FIN("Finalizar viaje"),
}

// PENDIENTE: guardado en el telefono, falta enviarlo.
// SINCRONIZADO: el central lo acepto (aplicado, o sin efecto porque ya estaba aplicado).
// CONFLICTO: el central lo registro pero no lo pudo aplicar al estado actual de la guia (p. ej. carga
//   despues de finalizar, guia reasignada o cancelada). No se reintenta; el responsable lo ve en la web.
// RECHAZADO: el central no lo acepto (datos invalidos, guia inexistente, no es chofer). No se reintenta.
enum class EstadoEvento { PENDIENTE, SINCRONIZADO, CONFLICTO, RECHAZADO }

data class Viaje(
    val id: String,
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
    // Estado con los eventos pendientes ya aplicados (actualizacion optimista)
    val estado: String,
    val estadoServidor: String,
    val version: Long?,
    val eventosPendientes: Int,
    // RECHAZADO + CONFLICTO
    val eventosRechazados: Int,
    // La empresa modifico datos de la guia (detectado al refrescar o al sincronizar); se muestra un aviso
    val avisoModificada: Boolean = false,
)

object ReglasViaje {

    // El estado que ve el chofer: el del servidor mas los eventos que todavia no se enviaron.
    fun estadoEfectivo(estadoServidor: String, pendientesEnOrden: List<TipoEvento>): String =
        pendientesEnOrden.fold(estadoServidor) { estado, tipo ->
            when (tipo) {
                TipoEvento.INICIO -> if (estado == EstadoGuia.ASIGNADA) EstadoGuia.EN_CURSO else estado
                TipoEvento.FIN -> if (estado == EstadoGuia.EN_CURSO) EstadoGuia.FINALIZADA else estado
                else -> estado
            }
        }

    // Acciones que la app ofrece segun el estado (las mismas reglas que valida el central)
    fun accionesPermitidas(estado: String): List<TipoEvento> = when (estado) {
        EstadoGuia.ASIGNADA -> listOf(TipoEvento.INICIO)
        EstadoGuia.EN_CURSO -> listOf(
            TipoEvento.CARGA_PARCIAL, TipoEvento.DESCARGA_PARCIAL, TipoEvento.INCIDENTE, TipoEvento.FIN,
        )
        else -> emptyList()
    }

    // Codigos de rechazo del central traducidos para el chofer
    fun mensajeRechazo(codigo: String?, detalle: String?): String = when (codigo) {
        "VIAJE_YA_INICIADO" -> "El viaje ya había sido iniciado."
        "VIAJE_NO_INICIADO" -> "El viaje no estaba iniciado."
        "VIAJE_FINALIZADO" -> "El viaje ya estaba finalizado."
        "VIAJE_YA_FINALIZADO" -> "El viaje ya estaba finalizado."
        "GUIA_REASIGNADA" -> "La empresa reasignó la guía a otro chofer."
        "GUIA_CANCELADA" -> "La empresa canceló la guía."
        "GUIA_NO_ASIGNADA" -> "La guía no está asignada."
        "no_encontrada" -> "La guía ya no está asignada a vos."
        "no_autorizado" -> "No estás habilitado como chofer."
        else -> detalle ?: codigo ?: "Rechazado por el servidor."
    }

    // Evento aceptado sin cambios porque su efecto ya estaba aplicado (p. ej. iniciado desde otro telefono)
    fun mensajeSinEfecto(codigo: String?, detalle: String?): String =
        "Sin cambios en el central: " + mensajeRechazo(codigo, detalle).replaceFirstChar { it.lowercase() }
}
