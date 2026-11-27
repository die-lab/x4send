package dev.diegocarli.x4send

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
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

enum class JobState { Queued, Converting, Uploading, Done, Failed }

data class UploadJob(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val state: JobState = JobState.Queued,
    val sent: Long = 0,
    val error: String? = null,
    val millis: Long = 0,
    val dest: String? = null,
    /** Es. "DOCX → EPUB" se il file e' stato convertito prima dell'invio. */
    val converted: String? = null,
    /** Cartella scelta dalla Libreria ("Carica qui"): vince su cartella base e ordinamento per autore. */
    val folder: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val prefs = Prefs(ctx)
    private val client = X4Client(ctx)

    val host = MutableStateFlow(prefs.host)
    val folder = MutableStateFlow(prefs.folder)
    val fast = MutableStateFlow(prefs.fast)
    val autoSort = MutableStateFlow(prefs.autoSort)
    val connection = MutableStateFlow("Non verificata")
    val showGuide = MutableStateFlow(!prefs.guideSeen)
    val jobs = MutableStateFlow<List<UploadJob>>(emptyList())

    val libPath = MutableStateFlow("/")
    val libItems = MutableStateFlow<List<RemoteFile>>(emptyList())
    val libBusy = MutableStateFlow(false)
    val libError = MutableStateFlow<String?>(null)

    /** Selettore della cartella di destinazione per "Sposta". */
    val moveItem = MutableStateFlow<RemoteFile?>(null)
    private var moveFrom = "/"
    val movePath = MutableStateFlow("/")
    val moveDirs = MutableStateFlow<List<String>>(emptyList())
    val moveBusy = MutableStateFlow(false)

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

    fun setAutoSort(v: Boolean) {
        autoSort.value = v
        prefs.autoSort = v
    }

    fun openGuide() {
        showGuide.value = true
    }

    fun closeGuide() {
        showGuide.value = false
        prefs.guideSeen = true
    }

    /** Cerca l'X4 sulla rete (utile con l'hotspot del telefono, dove l'IP cambia). */
    fun discover() {
        viewModelScope.launch {
            connection.value = "Ricerca dell'X4 sulla rete..."
            val found = try {
                client.discover()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            connection.value = if (found != null) {
                setHost(found.first)
                "Trovato: ${found.first} - firmware ${found.second.version} - ${found.second.mode}"
            } else {
                "X4 non trovato: sull'X4 e' aperto File Transfer? Altrimenti scrivi l'IP che mostra"
            }
        }
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

    fun enqueue(uris: List<Uri>, folder: String? = null) {
        val added = uris.map { uri ->
            val (name, size) = describeUri(uri)
            UploadJob(nextId++, uri, name, size, folder = folder)
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
        jobs.update { list ->
            list.filter { it.state == JobState.Queued || it.state == JobState.Converting || it.state == JobState.Uploading }
        }
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
        patch(job.id) { it.copy(state = JobState.Uploading, sent = 0, error = null, converted = null) }
        var temp: File? = null
        val started = System.currentTimeMillis()
        try {
            var size = job.size
            var sendName = job.name
            var opener: () -> InputStream = {
                ctx.contentResolver.openInputStream(job.uri) ?: throw IOException("File non leggibile")
            }
            if (!Formats.isNative(job.name)) {
                // L'X4 legge solo EPUB, TXT, XTC e BMP: il resto viene convertito sul telefono.
                if (!Formats.isConvertible(job.name)) throw IOException(Formats.unsupported(job.name))
                patch(job.id) { it.copy(state = JobState.Converting) }
                val source = opener
                val conv = withContext(Dispatchers.IO) { Converter.convert(ctx, job.name, source, ctx.cacheDir) }
                temp = conv.file
                sendName = conv.name
                size = conv.file.length()
                opener = { conv.file.inputStream() }
                val label = "${Formats.ext(job.name).uppercase()} → ${Formats.ext(conv.name).uppercase()}"
                patch(job.id) { it.copy(state = JobState.Uploading, converted = label) }
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
            val base = job.folder ?: normFolder(folder.value)
            var chosen = base
            if (job.folder == null && autoSort.value && sendName.endsWith(".epub", ignoreCase = true)) {
                // Cartella "Cognome, Nome" ricavata dai metadati dell'EPUB; senza autore resta la cartella base.
                val meta = try {
                    withContext(Dispatchers.IO) { EpubMeta.read(finalOpener) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                val authorDir = meta?.authorFolder
                if (authorDir != null) {
                    client.ensureDir(host.value, base, authorDir)
                    chosen = join(base, authorDir)
                }
            }
            val target = chosen
            patch(job.id) { it.copy(dest = target) }

            var last = 0L
            val progress: (Long) -> Unit = { sent ->
                if (sent - last >= 64 * 1024 || sent >= finalSize) {
                    last = sent
                    patch(job.id) { it.copy(sent = sent, size = finalSize) }
                }
            }

            if (fast.value) {
                try {
                    client.uploadWs(host.value, target, sendName, finalSize, finalOpener, progress)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Il WebSocket non ha funzionato: ripiega sull'upload HTTP classico.
                    last = 0
                    client.uploadHttp(host.value, target, sendName, finalSize, finalOpener, progress)
                }
            } else {
                client.uploadHttp(host.value, target, sendName, finalSize, finalOpener, progress)
            }
            val elapsed = System.currentTimeMillis() - started
            patch(job.id) { it.copy(state = JobState.Done, sent = finalSize, size = finalSize, millis = elapsed) }
            if (job.folder != null && job.folder == libPath.value) refreshLib()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            patch(job.id) { it.copy(state = JobState.Failed, error = describe(e)) }
        } catch (e: OutOfMemoryError) {
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
        if (!n.contains('.')) {
            // Nome senza estensione (succede con alcune condivisioni): la ricava dal tipo MIME.
            val mime = ctx.contentResolver.getType(uri)
            val ext = if (mime == "application/epub+zip") "epub" else mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
            if (ext != null) n += ".$ext"
        }
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

    fun renameRemote(f: RemoteFile, newName: String) {
        val name = newName.trim().replace(Regex("[/\\\\]"), "-")
        if (name.isEmpty() || name == f.name) return
        viewModelScope.launch {
            try {
                client.rename(host.value, join(libPath.value, f.name), name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                libError.value = describe(e)
            }
            refreshLib()
        }
    }

    fun startMove(f: RemoteFile) {
        moveFrom = libPath.value
        moveItem.value = f
        loadMoveDirs(libPath.value)
    }

    fun cancelMove() {
        moveItem.value = null
    }

    fun moveOpen(name: String) = loadMoveDirs(join(movePath.value, name))

    fun moveUp() {
        val p = movePath.value
        if (p != "/") loadMoveDirs(p.substringBeforeLast('/').ifEmpty { "/" })
    }

    private fun loadMoveDirs(path: String) {
        movePath.value = path
        viewModelScope.launch {
            moveBusy.value = true
            try {
                moveDirs.value = client.list(host.value, path).filter { it.isDir }.map { it.name }.sortedBy { it.lowercase() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                moveDirs.value = emptyList()
                libError.value = describe(e)
            } finally {
                moveBusy.value = false
            }
        }
    }

    /** Sposta il file scelto nella cartella aperta nel selettore. */
    fun confirmMove() {
        val f = moveItem.value ?: return
        val dest = movePath.value
        moveItem.value = null
        if (dest == moveFrom) return
        viewModelScope.launch {
            try {
                client.move(host.value, join(moveFrom, f.name), dest)
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
        is OutOfMemoryError -> "file troppo grande da convertire sul telefono"
        else -> translate(e.message ?: e.javaClass.simpleName)
    }

    /** Messaggi del web server CrossPoint resi in italiano. */
    private fun translate(m: String): String = when {
        m.contains("Target already exists") -> "esiste gia' un file con questo nome"
        m.contains("Folder already exists") -> "la cartella esiste gia'"
        m.contains("Only files can be renamed") -> "il firmware puo' rinominare solo i file, non le cartelle"
        m.contains("Only files can be moved") -> "il firmware puo' spostare solo i file, non le cartelle"
        m.contains("folder not empty") -> "la cartella non e' vuota: svuotala prima di eliminarla"
        m.contains("protected", ignoreCase = true) -> "elemento protetto dal firmware"
        m.contains("Destination not found") -> "cartella di destinazione non trovata"
        m.contains("Item not found") || m.contains("not found)") -> "file non trovato: aggiorna la libreria"
        m == "HTTP 404" || m.contains("Not found", ignoreCase = true) -> "funzione non disponibile: aggiorna il firmware CrossPoint"
        else -> m
    }
}
