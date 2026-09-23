package com.eljuliodev.servidormc

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = (application as NomadApplication).serverManager
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ServidorMcApp(manager)
                }
            }
        }
    }
}

private enum class Dest(val label: String, val icon: ImageVector) {
    Panel("Panel", Icons.Default.Home),
    Consola("Consola", Icons.AutoMirrored.Filled.List),
    Ajustes("Ajustes", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServidorMcApp(manager: ServerManager) {
    var dest by remember { mutableStateOf(Dest.Panel) }
    var ramMb by remember { mutableIntStateOf(2048) }
    val status by manager.status.collectAsState()
    val logs by manager.logs.collectAsState()
    val running = status == ServerManager.Status.Running
    val busy = status == ServerManager.Status.Starting || status == ServerManager.Status.Stopping

    Scaffold(
        topBar = { TopAppBar(title = { Text("NomadServer") }) }
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
                        status = status,
                        running = running,
                        busy = busy,
                        onToggle = {
                            if (running || status == ServerManager.Status.Starting) manager.stop()
                            else manager.start(ramMb)
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
private fun PanelScreen(
    status: ServerManager.Status,
    running: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusCard(status)
        Spacer(Modifier.height(32.dp))
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
    }
}

@Composable
private fun StatusCard(status: ServerManager.Status) {
    val running = status == ServerManager.Status.Running
    val dot = when (status) {
        ServerManager.Status.Running -> Color(0xFF4CAF50)
        ServerManager.Status.Starting, ServerManager.Status.Stopping -> Color(0xFFFFB300)
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
    val ip = remember(running) { if (running) LanAddress.get() else null }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conexión", style = MaterialTheme.typography.titleMedium)
            LabeledValue("LAN", ip?.let { "$it:25565" } ?: "—")
            LabeledValue("Playit.gg", "Desactivado")
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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
