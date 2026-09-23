package com.eljuliodev.servidormc
import androidx.compose.ui.tooling.preview.Preview

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private object NomadPalette {
    val Background = Color(0xFF0A0E17)
    val Surface = Color(0xFF11172A)
    val Card = Color(0xFF141B2E)
    val Border = Color(0xFF232C42)
    val Text = Color(0xFFE7ECF5)
    val Muted = Color(0xFF8B93AA)
    val Online = Color(0xFF34D399)
    val Warning = Color(0xFFFBBF24)
    val Accent = Color(0xFF3B82F6)
}

private val NomadColorScheme = darkColorScheme(
    primary = NomadPalette.Accent,
    onPrimary = Color.White,
    background = NomadPalette.Background,
    onBackground = NomadPalette.Text,
    surface = NomadPalette.Card,
    onSurface = NomadPalette.Text,
    surfaceVariant = NomadPalette.Surface,
    onSurfaceVariant = NomadPalette.Muted,
    outline = NomadPalette.Border,
    error = Color(0xFFEF4444),
)

/** Requiere res/font/monocraft.ttf (fuente pixelada estilo Minecraft, MIT — github.com/IdreesInc/Monocraft). */
private val MinecraftFont = FontFamily(Font(R.font.monocraft))

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as NomadApplication
        setContent {
            MaterialTheme(colorScheme = NomadColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot(app)
                }
            }
        }
    }
}

@Composable
private fun AppRoot(app: NomadApplication) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(ServerProfileStore.list(context)) }
    var selected by remember { mutableStateOf<ServerProfile?>(null) }
    var selectedDest by remember { mutableStateOf(Dest.Panel) }
    val current = selected

    if (current == null) {
        ServerListScreen(
            app = app,
            profiles = profiles,
            onOpen = { profile, dest -> selected = profile; selectedDest = dest },
            onCreate = { name, ram, maxPlayers ->
                val created = ServerProfileStore.add(context, name, ram, maxPlayers)
                profiles = ServerProfileStore.list(context)
                selected = created
                selectedDest = Dest.Panel
            },
            onDelete = { profile ->
                ServerProfileStore.remove(context, profile.id)
                profiles = ServerProfileStore.list(context)
            },
        )
    } else {
        ServerDetailScreen(
            profile = current,
            manager = app.managerFor(current.id),
            initialDest = selectedDest,
            onBack = { selected = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerListScreen(
    app: NomadApplication,
    profiles: List<ServerProfile>,
    onOpen: (ServerProfile, Dest) -> Unit,
    onCreate: (name: String, ramMb: Int, maxPlayers: Int) -> Unit,
    onDelete: (ServerProfile) -> Unit,
) {
    var showCreate by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerProfile?>(null) }

    Scaffold(
        topBar = { NomadTopBar() },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreate = true },
                containerColor = NomadPalette.Accent,
                contentColor = Color.White,
                shape = CircleShape,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Crear servidor", modifier = Modifier.size(28.dp))
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            SectionHeader()
            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No tienes servidores aún.\nToca + para crear uno.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NomadPalette.Muted,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        ServerRow(
                            profile = profile,
                            manager = app.managerFor(profile.id),
                            onOpen = { onOpen(profile, Dest.Panel) },
                            onSettings = { onOpen(profile, Dest.Ajustes) },
                            onViewLogs = { onOpen(profile, Dest.Consola) },
                            onDelete = { pendingDelete = profile },
                        )
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateServerDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, ram, maxPlayers -> onCreate(name, ram, maxPlayers); showCreate = false },
        )
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("¿Eliminar \"${profile.name}\"?") },
            text = { Text("Se borra el perfil. Los archivos del mundo quedan en el teléfono.") },
            confirmButton = {
                TextButton(onClick = { onDelete(profile); pendingDelete = null }) { Text("Eliminar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancelar") }
            },
        )
    }
}

@Composable
private fun NomadTopBar() {
    Surface(color = Color.Transparent) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_nomad_logo),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                "NomadServer",
                fontFamily = MinecraftFont,
                fontSize = 24.sp,
                color = NomadPalette.Text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { /* menú: pendiente */ }) {
                Icon(Icons.Default.Menu, contentDescription = "Menú", tint = NomadPalette.Text, modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Default.Dns,
            contentDescription = null,
            tint = NomadPalette.Muted,
            modifier = Modifier.padding(top = 4.dp).size(24.dp),
        )
        Column {
            Text("Mis Servidores", style = MaterialTheme.typography.titleLarge, fontFamily = MinecraftFont, color = NomadPalette.Text)
            Spacer(Modifier.height(4.dp))
            Text(
                "Gestiona y controla tus servidores de Minecraft",
                style = MaterialTheme.typography.bodySmall,
                color = NomadPalette.Muted,
            )
        }
    }
}

private val thumbnailGradients = listOf(
    listOf(Color(0xFF3A6B8A), Color(0xFF1B2740)),
    listOf(Color(0xFF7A3B8A), Color(0xFF241B40)),
    listOf(Color(0xFF3B8A5E), Color(0xFF1B402C)),
    listOf(Color(0xFF8A6B3B), Color(0xFF402F1B)),
    listOf(Color(0xFF3B5E8A), Color(0xFF1B2A40)),
)

@Composable
private fun ServerThumbnail(seed: Int, modifier: Modifier = Modifier) {
    val colors = thumbnailGradients[Math.floorMod(seed, thumbnailGradients.size)]
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(colors)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_nomad_logo),
            contentDescription = null,
            alpha = 0.85f,
            modifier = Modifier.size(26.dp),
        )
    }
}

@Composable
private fun StatusDot(status: ServerManager.Status) {
    val color = when (status) {
        ServerManager.Status.Running -> NomadPalette.Online
        ServerManager.Status.Starting, ServerManager.Status.Stopping -> NomadPalette.Warning
        ServerManager.Status.Error -> MaterialTheme.colorScheme.error
        ServerManager.Status.Stopped -> NomadPalette.Muted
    }
    Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun InfoLine(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, tint = NomadPalette.Muted, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = NomadPalette.Muted)
    }
}

@Composable
private fun RowActionButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = NomadPalette.Background,
        border = BorderStroke(1.dp, NomadPalette.Border),
        modifier = Modifier.size(34.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(icon, contentDescription = contentDescription, tint = NomadPalette.Muted, modifier = Modifier.size(18.dp))
        }
    }
}

private val ServerManager.Status.dashboardLabel: String
    get() = when (this) {
        ServerManager.Status.Stopped -> "Apagado"
        ServerManager.Status.Starting -> "Iniciando"
        ServerManager.Status.Running -> "En línea"
        ServerManager.Status.Stopping -> "Deteniendo"
        ServerManager.Status.Error -> "Error"
    }

@Composable
private fun ServerRow(
    profile: ServerProfile,
    manager: ServerManager,
    onOpen: () -> Unit,
    onSettings: () -> Unit,
    onViewLogs: () -> Unit,
    onDelete: () -> Unit,
) {
    val status by manager.status.collectAsState()
    val players by manager.players.collectAsState()
    val running = status == ServerManager.Status.Running
    val context = LocalContext.current
    val version = remember(profile.id, status) {
        ServerFiles.installedVersion(File(context.filesDir, "servers/${profile.id}")) ?: "Sin instalar"
    }
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NomadPalette.Card),
        border = BorderStroke(1.dp, NomadPalette.Border),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ServerThumbnail(seed = profile.id.hashCode(), modifier = Modifier.size(76.dp))
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        profile.name,
                        fontSize = 18.sp,
                        fontFamily = MinecraftFont,
                        color = NomadPalette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusDot(status)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        status.dashboardLabel,
                        color = if (running) NomadPalette.Online else NomadPalette.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = NomadPalette.Muted,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine(icon = Icons.Default.Inventory2, text = version)
                        InfoLine(icon = Icons.Default.Group, text = "${players.size}/${profile.maxPlayers}")
                    }
                    Spacer(Modifier.weight(1f))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RowActionButton(Icons.Default.Settings, "Ajustes", onSettings)
                        RowActionButton(Icons.AutoMirrored.Filled.Article, "Consola", onViewLogs)
                        Box {
                            RowActionButton(Icons.Default.MoreHoriz, "Más opciones") { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (running) "Detener" else "Iniciar") },
                                    leadingIcon = {
                                        Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        if (running) manager.stop() else manager.start(profile.ramMb, profile.maxPlayers)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Eliminar") },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = { menuOpen = false; onDelete() },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateServerDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, ramMb: Int, maxPlayers: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var ramMb by remember { mutableIntStateOf(2048) }
    var maxPlayers by remember { mutableIntStateOf(20) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo servidor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre") },
                    singleLine = true,
                )
                Text("RAM asignada: $ramMb MB")
                Slider(
                    value = ramMb.toFloat(),
                    onValueChange = { ramMb = it.toInt() },
                    valueRange = 1024f..4096f,
                    steps = 5,
                )
                Text("Jugadores máximos: $maxPlayers")
                Slider(
                    value = maxPlayers.toFloat(),
                    onValueChange = { maxPlayers = it.toInt() },
                    valueRange = 1f..40f,
                    steps = 38,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), ramMb, maxPlayers) }) {
                Text("Crear")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private enum class Dest(val label: String, val icon: ImageVector) {
    Panel("Panel", Icons.Default.Home),
    Consola("Consola", Icons.AutoMirrored.Filled.List),
    Ajustes("Ajustes", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerDetailScreen(
    profile: ServerProfile,
    manager: ServerManager,
    initialDest: Dest = Dest.Panel,
    onBack: () -> Unit,
) {
    var dest by remember(profile.id) { mutableStateOf(initialDest) }
    var ramMb by remember { mutableIntStateOf(profile.ramMb) }
    val status by manager.status.collectAsState()
    val logs by manager.logs.collectAsState()
    val running = status == ServerManager.Status.Running

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(profile.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                },
            )
        }
    ) { padding ->
        Row(modifier = Modifier.padding(padding).fillMaxSize()) {
            NavigationRail {
                Dest.entries.forEach { item ->
                    NavigationRailItem(
                        selected = dest == item,
                        onClick = { dest = item },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
            VerticalDivider()
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (dest) {
                    Dest.Panel -> PanelScreen(
                        manager = manager,
                        ramMb = ramMb,
                        onToggle = {
                            if (running || status == ServerManager.Status.Starting) manager.stop()
                            else manager.start(ramMb, profile.maxPlayers)
                        },
                    )
                    Dest.Consola -> ConsoleScreen(logs)
                    Dest.Ajustes -> SettingsScreen(ramMb, onRamChange = { ramMb = it })
                }
            }
        }
    }
}

private val ServerManager.Status.label: String
    get() = when (this) {
        ServerManager.Status.Stopped -> "Apagado"
        ServerManager.Status.Starting -> "Iniciando"
        ServerManager.Status.Running -> "Encendido"
        ServerManager.Status.Stopping -> "Deteniendo"
        ServerManager.Status.Error -> "Error"
    }

@Composable
private fun PanelScreen(manager: ServerManager, ramMb: Int, onToggle: () -> Unit) {
    val status by manager.status.collectAsState()
    val ramUsedMb by manager.ramUsedMb.collectAsState()
    val players by manager.players.collectAsState()
    val running = status == ServerManager.Status.Running
    val busy = status == ServerManager.Status.Starting || status == ServerManager.Status.Stopping

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusCard(status)
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard(
                modifier = Modifier.weight(1f),
                label = "RAM",
                value = "${ramUsedMb?.toString() ?: "—"} / $ramMb MB",
            )
            StatCard(modifier = Modifier.weight(1f), label = "Jugadores", value = "${players.size}")
        }
        Spacer(Modifier.height(24.dp))
        FilledIconButton(
            onClick = onToggle,
            enabled = !busy,
            modifier = Modifier.size(120.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (running || status == ServerManager.Status.Starting) {
                    Color(0xFF2E7D32)
                } else {
                    MaterialTheme.colorScheme.primary
                }
            ),
        ) {
            Text(
                text = if (running || status == ServerManager.Status.Starting) "STOP" else "START",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            when (status) {
                ServerManager.Status.Running -> "Toca para detener"
                ServerManager.Status.Starting -> "Arrancando…"
                ServerManager.Status.Stopping -> "Deteniendo…"
                else -> "Toca para encender"
            },
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(Modifier.height(32.dp))
        ConnectionCard(running)
        if (players.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            PlayersCard(players)
        }
    }
}

@Composable
private fun StatCard(modifier: Modifier = Modifier, label: String, value: String) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PlayersCard(players: Set<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Conectados", style = MaterialTheme.typography.titleMedium)
            players.forEach { name -> Text(name, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun StatusCard(status: ServerManager.Status) {
    val running = status == ServerManager.Status.Running
    val dot = when (status) {
        ServerManager.Status.Running -> NomadPalette.Online
        ServerManager.Status.Starting, ServerManager.Status.Stopping -> NomadPalette.Warning
        ServerManager.Status.Error -> MaterialTheme.colorScheme.error
        ServerManager.Status.Stopped -> MaterialTheme.colorScheme.outline
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(modifier = Modifier.size(12.dp), shape = CircleShape, color = dot) {}
            Column {
                Text(status.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running) "El servidor está aceptando conexiones" else "El servidor no está corriendo",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ConnectionCard(running: Boolean) {
    val context = LocalContext.current
    val ip = remember(running) { if (running) LanAddress.get() else null }
    val address = ip?.let { "$it:25565" }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conexión", style = MaterialTheme.typography.titleMedium)
            LabeledValue(
                label = "LAN",
                value = address ?: "—",
                onCopy = address?.let {
                    {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NomadServer", it))
                        Toast.makeText(context, "IP copiada", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            LabeledValue("Playit.gg", "Desactivado")
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            if (onCopy != null) {
                TextButton(onClick = onCopy, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("Copiar", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ConsoleScreen(logs: List<String>) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                enabled = logs.isNotEmpty(),
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("NomadServer", logs.joinToString("\n")))
                    Toast.makeText(context, "Consola copiada", Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("Copiar")
            }
        }
        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Aún no hay actividad. Enciende el servidor para ver la consola.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                reverseLayout = true,
            ) {
                items(logs) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SettingsScreen(ramMb: Int, onRamChange: (Int) -> Unit) {
    // ponytail: túnel y world border son visuales; se aplican cuando existan sus fases (5 y 6)
    var tunnel by remember { mutableStateOf(false) }
    var border by remember { mutableFloatStateOf(3000f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RAM asignada: $ramMb MB")
        Slider(
            value = ramMb.toFloat(),
            onValueChange = { onRamChange(it.toInt()) },
            valueRange = 1024f..4096f,
            steps = 5,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Túnel Playit.gg")
            Switch(checked = tunnel, onCheckedChange = { tunnel = it })
        }

        Text("World border: ${border.toInt()} bloques")
        Slider(value = border, onValueChange = { border = it }, valueRange = 1000f..10000f, steps = 8)
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0E17)
@Composable
private fun ServerRowPreview() {
    val context = LocalContext.current
    MaterialTheme(colorScheme = NomadColorScheme) {
        ServerRow(
            profile = ServerProfile(id = "preview", name = "Survival", ramMb = 2048, maxPlayers = 20),
            manager = remember { ServerManager(context, "preview") },
            onOpen = {},
            onSettings = {},
            onViewLogs = {},
            onDelete = {},
        )
    }
}