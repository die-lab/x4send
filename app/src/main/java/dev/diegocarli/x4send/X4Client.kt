package dev.diegocarli.x4send

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class Status(
    val version: String,
    val ip: String,
    val mode: String,
    val rssi: Int,
    val freeHeap: Long,
    val uptime: Long,
)

data class RemoteFile(val name: String, val size: Long, val isDir: Boolean)

/**
 * Client per il web server di CrossPoint Reader (HTTP porta 80, WebSocket porta 81).
 * Specifiche: docs/webserver-endpoints.md del repo crosspoint-reader.
 */
class X4Client(private val ctx: Context) {

    companion object {
        /** arduinoWebSockets su ESP32 accetta frame fino a ~15 KB: restiamo sotto. */
        const val WS_CHUNK = 8 * 1024
        private const val WS_QUEUE_LIMIT = 128 * 1024
        private const val HTTP_CHUNK = 16 * 1024

        /** Interfacce da non scandire: dati mobili, VPN, tunnel. */
        private val SKIP_IF = listOf("rmnet", "ccmni", "tun", "ppp", "dummy", "clat", "v4-", "ipsec", "lo")
    }

    /** Diventa true se Android rifiuta di legare i socket al WiFi (es. VPN): da li' in poi si usa la rete normale. */
    @Volatile
    private var bindBroken = false

    private fun clean(host: String): String =
        host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')

    /** Se il telefono e' connesso al WiFi usa quello, anche quando non ha internet (hotspot dell'X4). */
    @Suppress("DEPRECATION")
    private fun wifi(): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    private fun boundNetwork(): Network? = if (bindBroken) null else wifi()

    private fun isBindError(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 8) {
            val m = t.message.orEmpty()
            if (m.contains("EPERM") || m.contains("Binding socket", ignoreCase = true)) return true
            t = t.cause
            depth++
        }
        return false
    }

    /**
     * Esegue la richiesta legandola al WiFi (serve con l'hotspot dell'X4, che non ha internet).
     * Se Android lo vieta (EPERM, tipico con una VPN attiva) riprova sulla rete predefinita.
     */
    private suspend fun <T> withNet(block: suspend (OkHttpClient) -> T): T {
        val n = boundNetwork() ?: return block(http(null))
        return try {
            block(http(n))
        } catch (e: IOException) {
            if (!isBindError(e)) throw e
            bindBroken = true
            block(http(null))
        }
    }

    private fun http(network: Network?): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        network?.let { n ->
            b.socketFactory(n.socketFactory)
            b.dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    try {
                        n.getAllByName(hostname).toList()
                    } catch (e: Exception) {
                        Dns.SYSTEM.lookup(hostname)
                    }
            })
        }
        return b.build()
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }

    suspend fun status(host: String): Status = withContext(Dispatchers.IO) {
        val req = Request.Builder().url("http://${clean(host)}/api/status").build()
        withNet { c ->
            c.newCall(req).await().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val j = JSONObject(r.body?.string().orEmpty())
                Status(
                    version = j.optString("version", "?"),
                    ip = j.optString("ip", "?"),
                    mode = j.optString("mode", "?"),
                    rssi = j.optInt("rssi"),
                    freeHeap = j.optLong("freeHeap"),
                    uptime = j.optLong("uptime"),
                )
            }
        }
    }

    suspend fun list(host: String, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val url = "http://${clean(host)}/api/files".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()
        withNet { c ->
            c.newCall(Request.Builder().url(url).build()).await().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException(text.ifBlank { "HTTP ${r.code}" })
                val arr = JSONArray(text)
                List(arr.length()) { i ->
                    val o = arr.getJSONObject(i)
                    RemoteFile(o.getString("name"), o.optLong("size"), o.optBoolean("isDirectory"))
                }
            }
        }
    }

    private suspend fun postForm(host: String, endpoint: String, vararg fields: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            fields.forEach { fb.add(it.first, it.second) }
            val req = Request.Builder().url("http://${clean(host)}/$endpoint").post(fb.build()).build()
            withNet { c ->
                c.newCall(req).await().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw IOException(text.ifBlank { "HTTP ${r.code}" })
                    text
                }
            }
        }

    suspend fun mkdir(host: String, parent: String, name: String) {
        postForm(host, "mkdir", "name" to name, "path" to parent)
    }

    /** Crea la cartella se manca; se esiste gia' non e' un errore. */
    suspend fun ensureDir(host: String, parent: String, name: String) {
        try {
            mkdir(host, parent, name)
        } catch (e: IOException) {
            if (e.message?.contains("already exists", ignoreCase = true) != true) throw e
        }
    }

    suspend fun delete(host: String, path: String, isDir: Boolean) {
        postForm(host, "delete", "path" to path, "type" to if (isDir) "folder" else "file")
    }

    /** Upload multipart su POST /upload?path=... (campo "file"). */
    suspend fun uploadHttp(
        host: String,
        folder: String,
        name: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val fileBody = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                open().use { input ->
                    val buf = ByteArray(HTTP_CHUNK)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
            }
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", name, fileBody)
            .build()
        val url = "http://${clean(host)}/upload".toHttpUrl().newBuilder()
            .addQueryParameter("path", folder)
            .build()
        val req = Request.Builder().url(url).post(multipart).build()
        withNet { c ->
            c.newCall(req).await().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException(text.ifBlank { "HTTP ${r.code}" })
                Unit
            }
        }
    }

    /**
     * Upload veloce via WebSocket (porta 81):
     * START:<nome>:<dimensione>:<cartella> -> READY -> frame binari -> PROGRESS... -> DONE.
     */
    suspend fun uploadWs(
        host: String,
        folder: String,
        name: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ) = withContext(Dispatchers.IO) {
        withNet { c -> uploadWsWith(c, host, folder, name, size, open, onProgress) }
    }

    private suspend fun uploadWsWith(
        client: OkHttpClient,
        host: String,
        folder: String,
        name: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Long) -> Unit,
    ) {
        val ready = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val failure = AtomicReference<Throwable?>(null)

        fun fail(t: Throwable) {
            failure.compareAndSet(null, t)
            ready.completeExceptionally(t)
            done.completeExceptionally(t)
        }

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                when {
                    text == "READY" -> ready.complete(Unit)
                    text == "DONE" -> done.complete(Unit)
                    text.startsWith("ERROR:") -> fail(IOException(text.removePrefix("ERROR:")))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) fail(IOException("Connessione chiusa dall'X4"))
            }
        }

        val request = Request.Builder().url("http://${clean(host)}:81/").build()
        val ws = client.newWebSocket(request, listener)
        try {
            ws.send("START:$name:$size:$folder")
            withTimeout(10_000) { ready.await() }

            open().use { input ->
                val buf = ByteArray(WS_CHUNK)
                var sent = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    // Contropressione: non accumulare troppi dati nella coda interna di OkHttp.
                    while (ws.queueSize() > WS_QUEUE_LIMIT) {
                        failure.get()?.let { throw it }
                        delay(5)
                    }
                    failure.get()?.let { throw it }
                    if (!ws.send(buf.toByteString(0, n))) throw IOException("Invio WebSocket rifiutato")
                    sent += n
                    onProgress((sent - ws.queueSize()).coerceAtLeast(0))
                }
            }
            withTimeout(30_000) { done.await() }
            onProgress(size)
        } finally {
            ws.close(1000, null)
        }
    }

    // ---------------------------------------------------------------- ricerca dell'X4

    /**
     * Cerca l'X4: prima crosspoint.local, poi scandisce le reti locali del telefono
     * (WiFi a cui e' collegato o hotspot che sta condividendo) sulla porta 80.
     */
    suspend fun discover(): Pair<String, Status>? = withContext(Dispatchers.IO) {
        tryStatus("crosspoint.local")?.let { return@withContext "crosspoint.local" to it }
        val candidates = localSubnets()
            .flatMap { (prefix, own) -> (1..254).map { "$prefix.$it" }.filter { it != own } }
            .distinct()
        if (candidates.isEmpty()) return@withContext null
        val sem = Semaphore(48)
        val open = coroutineScope {
            candidates.map { ip -> async { sem.withPermit { if (portOpen(ip)) ip else null } } }.awaitAll().filterNotNull()
        }
        for (ip in open) {
            tryStatus(ip)?.let { return@withContext ip to it }
        }
        null
    }

    private suspend fun tryStatus(host: String): Status? = withTimeoutOrNull(4_000) {
        try {
            status(host)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun portOpen(ip: String): Boolean {
        val bound = boundNetwork()?.socketFactory
        return try {
            connect(bound ?: SocketFactory.getDefault(), ip)
            true
        } catch (e: IOException) {
            if (bound != null && isBindError(e)) {
                bindBroken = true
                try {
                    connect(SocketFactory.getDefault(), ip)
                    true
                } catch (_: IOException) {
                    false
                }
            } else {
                false
            }
        }
    }

    private fun connect(factory: SocketFactory, ip: String) {
        val s: Socket = factory.createSocket()
        s.use { it.connect(InetSocketAddress(ip, 80), 400) }
    }

    /** Coppie (prefisso /24, IP del telefono) per le interfacce locali utili. */
    private fun localSubnets(): List<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { ni -> ni.isUp && !ni.isLoopback && SKIP_IF.none { ni.name.startsWith(it) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { a -> a.hostAddress?.let { ip -> ip.substringBeforeLast('.') to ip } }
            .distinctBy { it.first }
    } catch (_: Exception) {
        emptyList()
    }
}
