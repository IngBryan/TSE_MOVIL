package uy.carga.movil.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uy.carga.movil.datos.CompresorFotos
import uy.carga.movil.datos.local.EventoEntity
import uy.carga.movil.dominio.EstadoEvento
import uy.carga.movil.dominio.EstadoGuia
import uy.carga.movil.dominio.ReglasViaje
import uy.carga.movil.dominio.TipoEvento
import uy.carga.movil.dominio.Viaje
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AZUL = Color(0xFF0B5394)
private val FORMATO_FECHA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

@Composable
fun App(vm: AppViewModel) {
    val sesion by vm.sesion.collectAsState()
    val ui by vm.ui.collectAsState()
    if (sesion == null) {
        PantallaLogin(ui, vm)
        return
    }
    val nav = rememberNavController()
    NavHost(nav, startDestination = "viajes") {
        composable("viajes") {
            PantallaViajes(vm) { id -> nav.navigate("viaje/${Uri.encode(id)}") }
        }
        composable("viaje/{id}") { entrada ->
            PantallaDetalle(vm, entrada.arguments?.getString("id") ?: "") { nav.popBackStack() }
        }
    }
}

// ===== Login =====

@Composable
private fun PantallaLogin(ui: EstadoUi, vm: AppViewModel) {
    val context = LocalContext.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("carga.uy", style = MaterialTheme.typography.displaySmall, color = AZUL, fontWeight = FontWeight.Bold)
            Text("App del chofer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(32.dp))
            if (ui.ingresando) {
                CircularProgressIndicator()
                Text("Ingresando…", Modifier.padding(top = 8.dp))
            } else {
                Button(onClick = {
                    vm.urlLogin()?.let {
                        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(it))
                    }
                }) { Text("Ingresar con gub.uy") }
            }
            ui.errorLogin?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ===== Mis viajes =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantallaViajes(vm: AppViewModel, abrir: (String) -> Unit) {
    val viajes by vm.viajes.collectAsState()
    val ui by vm.ui.collectAsState()
    val sesion by vm.sesion.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val alcance = rememberCoroutineScope()
    var confirmarSalida by remember { mutableIntStateOf(-1) } // -1 = cerrado; si no, cantidad de pendientes

    LaunchedEffect(Unit) { vm.refrescar() }
    MensajeEnSnackbar(ui.mensaje, snackbar, vm)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mis viajes") },
                actions = {
                    IconButton(onClick = { vm.refrescar(); vm.sincronizarAhora() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Actualizar")
                    }
                    IconButton(onClick = { alcance.launch { confirmarSalida = vm.cantidadPendientes() } }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Salir")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = ui.actualizando,
            onRefresh = { vm.refrescar(); vm.sincronizarAhora() },
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
                item { Avisos(ui, sesion?.vencida() == true, vm) }
                if (viajes.isEmpty()) {
                    item {
                        Text(
                            if (ui.noEsChofer) "Tu usuario no está habilitado como chofer." else "No tenés viajes pendientes",
                            Modifier.fillMaxWidth().padding(top = 48.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(viajes, key = { it.id }) { v ->
                    TarjetaViaje(v) { abrir(v.id) }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }

    if (confirmarSalida >= 0) {
        AlertDialog(
            onDismissRequest = { confirmarSalida = -1 },
            title = { Text("Salir") },
            text = {
                Text(
                    if (confirmarSalida > 0) "Tenés $confirmarSalida evento(s) sin enviar al central. Si salís se pierden. ¿Salir igual?"
                    else "Se borran los viajes guardados en el teléfono.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmarSalida = -1; vm.salir() }) { Text("Salir") } },
            dismissButton = { TextButton(onClick = { confirmarSalida = -1 }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Avisos(ui: EstadoUi, sesionVencida: Boolean, vm: AppViewModel) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        if (ui.conectado == false) {
            val fecha = ui.ultimaActualizacion?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(FORMATO_FECHA_HORA)
            }
            Aviso(if (fecha != null) "Sin conexión, datos del $fecha" else "Sin conexión, todavía no se descargaron viajes")
        }
        if (sesionVencida) {
            Aviso("Tu sesión venció. Podés seguir registrando eventos; para enviarlos volvé a ingresar.") {
                TextButton(onClick = {
                    vm.urlLogin()?.let { CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it)) }
                }) { Text("Ingresar") }
            }
        }
    }
}

@Composable
private fun Aviso(texto: String, accion: @Composable (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(texto, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            accion?.invoke()
        }
    }
}

@Composable
private fun TarjetaViaje(v: Viaje, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.id, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                ChipEstado(v.estado)
            }
            Text("${v.origen} → ${v.destino}", style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(v.rubroCliente.ifBlank { null }, v.tipoCarga, "%,d kg".format(v.pesoCargaKg).replace(',', '.')).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            Text("${fecha(v.fecha)} · ${v.matricula ?: "sin vehículo"} · ${v.empresa ?: ""}", style = MaterialTheme.typography.bodySmall)
            if (v.eventosPendientes > 0) {
                Text("${v.eventosPendientes} evento(s) sin enviar", color = Color(0xFFB26A00), style = MaterialTheme.typography.bodySmall)
            }
            if (v.avisoModificada) {
                Text("La empresa modificó esta guía", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            }
            if (v.eventosRechazados > 0) {
                Text("${v.eventosRechazados} evento(s) rechazado(s) o en conflicto", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChipEstado(estado: String) {
    val color = when (estado) {
        EstadoGuia.EN_CURSO -> AZUL
        EstadoGuia.FINALIZADA -> Color(0xFF2E7D32)
        else -> Color(0xFF616161)
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(EstadoGuia.etiqueta(estado), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), color = Color.White,
            style = MaterialTheme.typography.labelMedium)
    }
}

// ===== Detalle =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PantallaDetalle(vm: AppViewModel, id: String, volver: () -> Unit) {
    val context = LocalContext.current
    val datos by remember(id) { vm.viaje(id) }.collectAsState(initial = null to emptyList())
    val ui by vm.ui.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var accion by remember { mutableStateOf<TipoEvento?>(null) }
    MensajeEnSnackbar(ui.mensaje, snackbar, vm)

    // Ubicacion opcional de los eventos: se pide una vez
    val permisos = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    LaunchedEffect(Unit) {
        val falta = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        if (falta) permisos.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(id) },
                navigationIcon = {
                    IconButton(onClick = volver) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val (viaje, eventos) = datos
        if (viaje == null) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("El viaje ya no está en el teléfono.")
            }
            return@Scaffold
        }
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${viaje.origen} → ${viaje.destino}", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                ChipEstado(viaje.estado)
            }
            if (viaje.avisoModificada) {
                Aviso("La empresa modificó esta guía mientras no la veías. Ya se muestran los datos actualizados.") {
                    TextButton(onClick = { vm.descartarAviso(viaje.id) }) { Text("Entendido") }
                }
            }
            Dato("Empresa", "${viaje.empresa ?: "-"} (nro. ${viaje.nroEmpresa ?: "-"})")
            Dato("Fecha", fecha(viaje.fecha))
            Dato("Rubro", viaje.rubroCliente)
            Dato("Tipo de carga", viaje.tipoCarga ?: "-")
            Dato("Carga", "%,d kg".format(viaje.pesoCargaKg).replace(',', '.'))
            Dato("Vehículo", listOfNotNull(viaje.matricula, viaje.vehiculo).joinToString(" · ").ifEmpty { "-" })

            Spacer(Modifier.height(8.dp))
            val acciones = ReglasViaje.accionesPermitidas(viaje.estado)
            if (acciones.isEmpty()) {
                Text("No hay acciones disponibles para este viaje.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            acciones.forEach { tipo ->
                if (tipo == TipoEvento.INICIO || tipo == TipoEvento.FIN) {
                    Button(onClick = { accion = tipo }, Modifier.fillMaxWidth()) { Text(tipo.etiqueta) }
                } else {
                    OutlinedButton(onClick = { accion = tipo }, Modifier.fillMaxWidth()) { Text(tipo.etiqueta) }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Text("Eventos", style = MaterialTheme.typography.titleMedium)
            if (eventos.isEmpty()) Text("Todavía no registraste eventos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            eventos.forEach { FilaEvento(it) }
            if (eventos.any { it.estado == EstadoEvento.PENDIENTE.name }) {
                TextButton(onClick = { vm.sincronizarAhora() }) { Text("Enviar pendientes ahora") }
            }
        }
    }

    accion?.let { tipo ->
        DialogoEvento(tipo, onCancelar = { accion = null }) { descripcion, foto ->
            accion = null
            vm.registrar(id, tipo, descripcion, foto)
        }
    }
}

@Composable
private fun Dato(etiqueta: String, valor: String) {
    Row {
        Text(etiqueta, Modifier.width(112.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(valor)
    }
}

@Composable
private fun FilaEvento(e: EventoEntity) {
    val tipo = runCatching { TipoEvento.valueOf(e.tipo).etiqueta }.getOrDefault(e.tipo)
    val hora = runCatching { OffsetDateTime.parse(e.fechaHora).atZoneSameInstant(ZoneId.systemDefault()).format(FORMATO_FECHA_HORA) }
        .getOrDefault(e.fechaHora)
    val (texto, color) = when (e.estado) {
        EstadoEvento.SINCRONIZADO.name -> "Enviado" to Color(0xFF2E7D32)
        EstadoEvento.RECHAZADO.name -> "Rechazado" to MaterialTheme.colorScheme.error
        EstadoEvento.CONFLICTO.name -> "En conflicto" to MaterialTheme.colorScheme.error
        else -> (if (e.intentos > 0) "Pendiente (reintentando)" else "Pendiente") to Color(0xFFB26A00)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row {
            Text("$tipo · $hora", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text(texto, color = color, style = MaterialTheme.typography.labelLarge)
        }
        e.descripcion?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (e.rutaFoto != null) Text("Con foto", style = MaterialTheme.typography.bodySmall)
        when {
            e.motivo == null -> Unit
            e.estado == EstadoEvento.RECHAZADO.name -> Text(e.motivo, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            // Registrado en el central pero sin aplicar: no se reintenta, la empresa lo ve en la web
            e.estado == EstadoEvento.CONFLICTO.name -> Text(
                "${e.motivo} Quedó registrado para la empresa; no cambió el viaje.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
            e.estado == EstadoEvento.SINCRONIZADO.name -> Text(e.motivo, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            else -> Text("Último intento: ${e.motivo}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// Confirmacion de INICIO / FIN, o descripcion (+ foto en incidentes) de los demas eventos
@Composable
private fun DialogoEvento(tipo: TipoEvento, onCancelar: () -> Unit, onConfirmar: (String?, ByteArray?) -> Unit) {
    val context = LocalContext.current
    val alcance = rememberCoroutineScope()
    var descripcion by rememberSaveable { mutableStateOf("") }
    var foto by remember { mutableStateOf<ByteArray?>(null) }
    var procesandoFoto by remember { mutableStateOf(false) }
    var archivoCamara by remember { mutableStateOf<Uri?>(null) }
    val conDescripcion = tipo != TipoEvento.INICIO && tipo != TipoEvento.FIN
    val conFoto = tipo == TipoEvento.INCIDENTE

    fun cargar(uri: Uri) {
        procesandoFoto = true
        alcance.launch {
            foto = runCatching { withContext(Dispatchers.IO) { CompresorFotos.comprimir(context, uri) } }.getOrNull()
            procesandoFoto = false
        }
    }
    val camara = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) archivoCamara?.let(::cargar)
    }
    val galeria = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::cargar)
    }
    // Sin Photo Picker del sistema (algunas imagenes de emulador y telefonos) PICK_IMAGES no tiene quien
    // lo atienda: se usa el selector de archivos.
    val archivos = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(::cargar)
    }

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(tipo.etiqueta) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!conDescripcion) {
                    Text(if (tipo == TipoEvento.INICIO) "¿Iniciar el viaje ahora?" else "¿Finalizar el viaje? Después no se pueden registrar más eventos.")
                } else {
                    OutlinedTextField(
                        value = descripcion,
                        onValueChange = { if (it.length <= 500) descripcion = it },
                        label = { Text(if (tipo == TipoEvento.INCIDENTE) "Qué pasó (obligatorio)" else "Descripción (opcional)") },
                        minLines = 2,
                    )
                }
                if (conFoto) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val archivo = File(File(context.cacheDir, "camara").apply { mkdirs() }, "foto.jpg")
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fotos", archivo)
                            archivoCamara = uri
                            camara.launch(uri)
                        }) { Text("Cámara") }
                        OutlinedButton(onClick = {
                            try {
                                galeria.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } catch (e: ActivityNotFoundException) {
                                archivos.launch("image/*")
                            }
                        }) { Text("Galería") }
                    }
                    when {
                        procesandoFoto -> Text("Procesando foto…")
                        foto != null -> Text("Foto adjunta (${foto!!.size / 1024} KB)")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !procesandoFoto && (tipo != TipoEvento.INCIDENTE || descripcion.isNotBlank()),
                onClick = { onConfirmar(descripcion.takeIf { conDescripcion && it.isNotBlank() }, foto) },
            ) { Text("Confirmar") }
        },
        dismissButton = { TextButton(onClick = onCancelar) { Text("Cancelar") } },
    )
}

@Composable
private fun MensajeEnSnackbar(mensaje: String?, snackbar: SnackbarHostState, vm: AppViewModel) {
    LaunchedEffect(mensaje) {
        if (mensaje != null) {
            vm.mensajeMostrado()
            snackbar.showSnackbar(mensaje)
        }
    }
}

private fun fecha(iso: String): String =
    runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrDefault(iso)
