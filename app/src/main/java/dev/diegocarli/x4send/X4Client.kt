package dev.diegocarli.x4send

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
    }

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

    private fun http(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
        wifi()?.let { n ->
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
        http().newCall(req).await().use { r ->
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

    suspend fun list(host: String, path: String): List<RemoteFile> = withContext(Dispatchers.IO) {
        val url = "http://${clean(host)}/api/files".toHttpUrl().newBuilder()
            .addQueryParameter("path", path)
            .build()
        http().newCall(Request.Builder().url(url).build()).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(text.ifBlank { "HTTP ${r.code}" })
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                RemoteFile(o.getString("name"), o.optLong("size"), o.optBoolean("isDirectory"))
            }
        }
    }

    private suspend fun postForm(host: String, endpoint: String, vararg fields: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            fields.forEach { fb.add(it.first, it.second) }
            val req = Request.Builder().url("http://${clean(host)}/$endpoint").post(fb.build()).build()
            http().newCall(req).await().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) throw IOException(text.ifBlank { "HTTP ${r.code}" })
                text
            }
        }

    suspend fun mkdir(host: String, parent: String, name: String) {
        postForm(host, "mkdir", "name" to name, "path" to parent)
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
        http().newCall(req).await().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(text.ifBlank { "HTTP ${r.code}" })
            Unit
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
        val ws = http().newWebSocket(request, listener)
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
}
