package dev.diegocarli.x4send

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale

fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

@Composable
fun App(vm: MainViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("↑") },
                    label = { Text("Invia") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = {
                        tab = 1
                        vm.refreshLib()
                    },
                    icon = { Text("☰") },
                    label = { Text("Libreria") },
                )
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            if (tab == 0) SendScreen(vm) else LibraryScreen(vm)
        }
    }
}

@Composable
fun SendScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val host by vm.host.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val fast by vm.fast.collectAsStateWithLifecycle()
    val autoSort by vm.autoSort.collectAsStateWithLifecycle()
    val connection by vm.connection.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val showGuide by vm.showGuide.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach {
            try {
                ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        if (uris.isNotEmpty()) vm.enqueue(uris)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("X4 Send", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::openGuide) { Text("Come collegarsi") }
            }
        }

        item {
            OutlinedTextField(
                value = host,
                onValueChange = vm::setHost,
                label = { Text("Indirizzo dell'X4") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setHost("crosspoint.local") }) { Text("crosspoint.local") }
                TextButton(onClick = { vm.setHost("192.168.4.1") }) { Text("192.168.4.1 (hotspot X4)") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = vm::testConnection) { Text("Prova connessione") }
                    OutlinedButton(onClick = vm::discover) { Text("Cerca X4") }
                }
                Text(connection, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            OutlinedTextField(
                value = folder,
                onValueChange = vm::setFolder,
                label = { Text("Cartella base sull'X4") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Organizza per autore")
                    Text(
                        "Cartella \"Cognome, Nome\" letta dall'EPUB, creata se manca",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoSort, onCheckedChange = vm::setAutoSort)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Upload veloce (WebSocket)")
                    Text(
                        "Se fallisce ripiega sull'upload HTTP",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = fast, onCheckedChange = vm::setFast)
            }
        }
        item {
            Button(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scegli file da inviare") }
        }
        item {
            Text(
                "EPUB, TXT, XTC e BMP partono cosi' come sono. DOCX, ODT, PDF, RTF, FB2, HTML e Markdown " +
                    "vengono convertiti in EPUB, le immagini in BMP. Puoi anche condividere un file da qualsiasi app scegliendo X4 Send.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (jobs.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Coda", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearFinished) { Text("Svuota completati") }
                }
            }
            items(jobs, key = { it.id }) { job -> JobRow(job, onRetry = { vm.retry(job.id) }) }
        }
        item { Box(Modifier.padding(bottom = 16.dp)) }
    }

    if (showGuide) GuideDialog(onDismiss = vm::closeGuide)
}

@Composable
private fun GuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Come collegare l'X4") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ovunque: hotspot del telefono (consigliato)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "1. Sul telefono attiva l'hotspot personale.\n" +
                        "2. Sull'X4 apri File Transfer e scegli di collegarti a una rete WiFi (non \"Create Hotspot\").\n" +
                        "3. Seleziona l'hotspot del telefono e inserisci la password: solo la prima volta, poi l'X4 lo ricorda.\n" +
                        "4. Qui premi \"Cerca X4\", oppure scrivi l'IP che compare sullo schermo dell'X4.\n" +
                        "5. \"Prova connessione\", poi \"Scegli file da inviare\".",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("A casa", style = MaterialTheme.typography.titleSmall)
                Text(
                    "X4 e telefono sullo stesso WiFi di casa: in File Transfer scegli la rete di casa, " +
                        "poi qui usa crosspoint.local o l'IP mostrato.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Hotspot dell'X4", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Sull'X4 \"Create Hotspot\", collega il telefono alla rete CrossPoint-Reader e usa 192.168.4.1. " +
                        "Se Android avvisa che la rete non ha internet, scegli di restare connesso.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Se non si collega", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Con una VPN attiva escludi X4 Send dalla VPN (split tunneling). " +
                        "Controlla che sull'X4 sia ancora aperta la schermata File Transfer.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Ho capito") } },
    )
}

@Composable
private fun JobRow(job: UploadJob, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(job.name, style = MaterialTheme.typography.bodyMedium)
        when (job.state) {
            JobState.Queued -> Text("In coda", style = MaterialTheme.typography.bodySmall)
            JobState.Converting -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Conversione in corso...", style = MaterialTheme.typography.bodySmall)
            }
            JobState.Uploading -> {
                val p = if (job.size > 0) job.sent.toFloat() / job.size else 0f
                LinearProgressIndicator(progress = { p.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text("${fmtSize(job.sent)} / ${fmtSize(job.size)}", style = MaterialTheme.typography.bodySmall)
            }
            JobState.Done -> {
                val secs = job.millis / 1000.0
                val speed = if (job.millis > 0) job.size / 1024.0 / secs else 0.0
                Text(
                    String.format(
                        Locale.US,
                        "Inviato in %s%s - %s, %.1f s (%.0f KB/s)",
                        job.dest ?: "/",
                        job.converted?.let { " ($it)" } ?: "",
                        fmtSize(job.size),
                        secs,
                        speed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            JobState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Errore: ${job.error ?: "sconosciuto"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRetry) { Text("Riprova") }
            }
        }
        HorizontalDivider()
    }
}

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val path by vm.libPath.collectAsStateWithLifecycle()
    val entries by vm.libItems.collectAsStateWithLifecycle()
    val busy by vm.libBusy.collectAsStateWithLifecycle()
    val error by vm.libError.collectAsStateWithLifecycle()

    var toDelete by remember { mutableStateOf<RemoteFile?>(null) }
    var showMkdir by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text("Libreria sull'X4", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))
        Text(path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::upDir, enabled = path != "/") { Text("Su") }
            OutlinedButton(onClick = vm::refreshLib) { Text("Aggiorna") }
            OutlinedButton(onClick = { showMkdir = true }) { Text("Nuova cartella") }
        }
        if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(entries, key = { it.name }) { f ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = f.isDir) { vm.openDir(f.name) }
                        .padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(if (f.isDir) "[cartella] ${f.name}" else f.name)
                        if (!f.isDir) Text(fmtSize(f.size), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { toDelete = f }) { Text("Elimina") }
                }
                HorizontalDivider()
            }
        }
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare?") },
            text = { Text(f.name) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteRemote(f)
                    toDelete = null
                }) { Text("Elimina") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }

    if (showMkdir) {
        AlertDialog(
            onDismissRequest = { showMkdir = false },
            title = { Text("Nuova cartella") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Nome") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        vm.makeDir(newName)
                        newName = ""
                        showMkdir = false
                    },
                ) { Text("Crea") }
            },
            dismissButton = { TextButton(onClick = { showMkdir = false }) { Text("Annulla") } },
        )
    }
}
