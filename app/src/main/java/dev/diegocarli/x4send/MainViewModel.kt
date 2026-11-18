package dev.diegocarli.x4send

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

enum class JobState { Queued, Uploading, Done, Failed }

data class UploadJob(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val state: JobState = JobState.Queued,
    val sent: Long = 0,
    val error: String? = null,
    val millis: Long = 0,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val prefs = Prefs(ctx)
    private val client = X4Client(ctx)

    val host = MutableStateFlow(prefs.host)
    val folder = MutableStateFlow(prefs.folder)
    val fast = MutableStateFlow(prefs.fast)
    val connection = MutableStateFlow("Non verificata")
    val jobs = MutableStateFlow<List<UploadJob>>(emptyList())

    val libPath = MutableStateFlow("/")
    val libItems = MutableStateFlow<List<RemoteFile>>(emptyList())
    val libBusy = MutableStateFlow(false)
    val libError = MutableStateFlow<String?>(null)

    private var nextId = 1L
    private var worker: Job? = null

    fun setHost(v: String) {
        host.value = v
        prefs.host = v
    }

    fun setFolder(v: String) {
        folder.value = v
        prefs.folder = v
    }

    fun setFast(v: Boolean) {
        fast.value = v
        prefs.fast = v
    }

    fun testConnection() {
        viewModelScope.launch {
            connection.value = "Connessione..."
            connection.value = try {
                val s = client.status(host.value)
                buildString {
                    append("OK - firmware ${s.version} - ${s.mode} - ${s.ip}")
                    if (s.mode == "STA") append(" - ${s.rssi} dBm")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Errore: ${describe(e)}"
            }
        }
    }

    // ---------------------------------------------------------------- coda di upload

    fun enqueue(uris: List<Uri>) {
        val added = uris.map { uri ->
            val (name, size) = describeUri(uri)
            UploadJob(nextId++, uri, name, size)
        }
        if (added.isEmpty()) return
        jobs.update { it + added }
        pump()
    }

    fun retry(id: Long) {
        patch(id) { it.copy(state = JobState.Queued, sent = 0, error = null) }
        pump()
    }

    fun clearFinished() {
        jobs.update { list -> list.filter { it.state == JobState.Queued || it.state == JobState.Uploading } }
    }

    private fun pump() {
        if (worker?.isActive == true) return
        worker = viewModelScope.launch {
            while (true) {
                val next = jobs.value.firstOrNull { it.state == JobState.Queued } ?: break
                runJob(next)
            }
        }
    }

    private fun patch(id: Long, f: (UploadJob) -> UploadJob) {
        jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    private suspend fun runJob(job: UploadJob) {
        patch(job.id) { it.copy(state = JobState.Uploading, sent = 0, error = null) }
        var temp: File? = null
        val started = System.currentTimeMillis()
        try {
            var size = job.size
            var opener: () -> InputStream = {
                ctx.contentResolver.openInputStream(job.uri) ?: throw IOException("File non leggibile")
            }
            if (size <= 0) {
                // Dimensione sconosciuta: serve per Content-Length / START, quindi copia in cache.
                val f = File.createTempFile("x4", ".tmp", ctx.cacheDir)
                temp = f
                val source = opener
                withContext(Dispatchers.IO) {
                    source().use { input -> f.outputStream().use { out -> input.copyTo(out) } }
                }
                size = f.length()
                opener = { f.inputStream() }
            }
            val finalSize = size
            val finalOpener = opener
            val target = normFolder(folder.value)

            var last = 0L
            val progress: (Long) -> Unit = { sent ->
                if (sent - last >= 64 * 1024 || sent >= finalSize) {
                    last = sent
                    patch(job.id) { it.copy(sent = sent, size = finalSize) }
                }
            }

            if (fast.value) {
                try {
                    client.uploadWs(host.value, target, job.name, finalSize, finalOpener, progress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Il WebSocket non ha funzionato: ripiega sull'upload HTTP classico.
                    last = 0
                    client.uploadHttp(host.value, target, job.name, finalSize, finalOpener, progress)
                }
            } else {
                client.uploadHttp(host.value, target, job.name, finalSize, finalOpener, progress)
            }
            val elapsed = System.currentTimeMillis() - started
            patch(job.id) { it.copy(state = JobState.Done, sent = finalSize, size = finalSize, millis = elapsed) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            patch(job.id) { it.copy(state = JobState.Failed, error = describe(e)) }
        } finally {
            temp?.delete()
        }
    }

    private fun describeUri(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
        }
        var n = name ?: uri.lastPathSegment ?: "libro.epub"
        // ':' e' il separatore del protocollo WebSocket, '/' e '\' non sono ammessi nei nomi.
        n = n.replace(Regex("[:/\\\\]"), "-")
        if (!n.contains('.') && ctx.contentResolver.getType(uri) == "application/epub+zip") n += ".epub"
        return n to size
    }

    // ---------------------------------------------------------------- libreria sul dispositivo

    fun refreshLib() {
        viewModelScope.launch {
            libBusy.value = true
            libError.value = null
            try {
                libItems.value = client.list(host.value, libPath.value)
                    .sortedWith(compareByDescending<RemoteFile> { it.isDir }.thenBy { it.name.lowercase() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libItems.value = emptyList()
                libError.value = describe(e)
            } finally {
                libBusy.value = false
            }
        }
    }

    fun openDir(name: String) {
        libPath.value = join(libPath.value, name)
        refreshLib()
    }

    fun upDir() {
        val p = libPath.value
        if (p == "/") return
        libPath.value = p.substringBeforeLast('/').ifEmpty { "/" }
        refreshLib()
    }

    fun deleteRemote(f: RemoteFile) {
        viewModelScope.launch {
            try {
                client.delete(host.value, join(libPath.value, f.name), f.isDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libError.value = describe(e)
            }
            refreshLib()
        }
    }

    fun makeDir(name: String) {
        viewModelScope.launch {
            try {
                client.mkdir(host.value, libPath.value, name.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libError.value = describe(e)
            }
            refreshLib()
        }
    }

    // ---------------------------------------------------------------- utilita'

    private fun join(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

    private fun normFolder(f: String): String {
        var s = f.trim()
        if (!s.startsWith("/")) s = "/$s"
        if (s.length > 1) s = s.trimEnd('/')
        return s.ifEmpty { "/" }
    }

    private fun describe(e: Throwable): String = when (e) {
        is UnknownHostException -> "indirizzo non trovato: usa l'IP mostrato sullo schermo dell'X4"
        is SocketTimeoutException -> "timeout: l'X4 e' in modalita' File Transfer e sullo stesso WiFi?"
        is ConnectException, is NoRouteToHostException -> "X4 non raggiungibile: controlla WiFi e modalita' File Transfer"
        is SecurityException -> "permesso di lettura scaduto: riseleziona il file"
        else -> e.message ?: e.javaClass.simpleName
    }
}
