package com.eljuliodev.servidormc

import android.os.Bundle
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ServidorMcApp()
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
private fun ServidorMcApp() {
    var dest by remember { mutableStateOf(Dest.Panel) }
    // ponytail: estado simulado; conectar a un ServerController cuando exista (Fase 3)
    var running by remember { mutableStateOf(false) }
    val logs = remember { mutableListOf<String>() }

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
                    Dest.Panel -> PanelScreen(running) { running = !running }
                    Dest.Consola -> ConsoleScreen(logs)
                    Dest.Ajustes -> SettingsScreen()
                }
            }
        }
    }
}

@Composable
private fun PanelScreen(running: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusCard(running)
        Spacer(Modifier.height(32.dp))
        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(120.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (running) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
            ),
        ) {
            Text(
                text = if (running) "STOP" else "START",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(if (running) "Toca para detener" else "Toca para encender", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(32.dp))
        ConnectionCard(running)
    }
}

@Composable
private fun StatusCard(running: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = CircleShape,
                color = if (running) Color(0xFF4CAF50) else MaterialTheme.colorScheme.outline,
            ) {}
            Column {
                Text(if (running) "Encendido" else "Apagado", style = MaterialTheme.typography.titleMedium)
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conexión", style = MaterialTheme.typography.titleMedium)
            LabeledValue("LAN", if (running) "192.168.0.0:25565" else "—")
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
    if (logs.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "Aún no hay actividad. Enciende el servidor para ver la consola.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            items(logs) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SettingsScreen() {
    // ponytail: ajustes visuales; persistir/aplicar cuando exista el motor (Fase 3)
    var ram by remember { mutableFloatStateOf(2048f) }
    var tunnel by remember { mutableStateOf(false) }
    var border by remember { mutableFloatStateOf(3000f) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("RAM asignada: ${ram.toInt()} MB")
        Slider(value = ram, onValueChange = { ram = it }, valueRange = 1024f..4096f, steps = 5)

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
