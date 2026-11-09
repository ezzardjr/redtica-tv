package com.redtica.camarastv

import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Autenticación Digest (la que usan por defecto las cámaras Hikvision en ISAPI). */
class DigestAuthenticator(private val user: String, private val pass: String) : Authenticator {
    private var nc = 0

    override fun authenticate(route: Route?, response: Response): Request? {
        var prior = response.priorResponse
        var count = 1
        while (prior != null) { count++; prior = prior.priorResponse }
        if (count >= 3) return null

        val headers = response.headers("WWW-Authenticate")
        val digest = headers.firstOrNull { it.trim().startsWith("Digest", true) }
        if (digest == null) {
            return if (headers.any { it.trim().startsWith("Basic", true) })
                response.request.newBuilder().header("Authorization", Credentials.basic(user, pass)).build()
            else null
        }
        val p = parse(digest.trim().substring(6))
        val realm = p["realm"] ?: ""
        val nonce = p["nonce"] ?: ""
        val opaque = p["opaque"]
        val algorithm = p["algorithm"]
        val qop = p["qop"]?.split(",")?.map { it.trim() }?.firstOrNull { it == "auth" }
        val req = response.request
        val uri = req.url.encodedPath + (req.url.encodedQuery?.let { "?$it" } ?: "")
        val ha1 = md5("$user:$realm:$pass")
        val ha2 = md5("${req.method}:$uri")
        nc++
        val ncS = String.format("%08x", nc)
        val cnonce = md5(System.nanoTime().toString()).substring(0, 16)
        val resp = if (qop != null) md5("$ha1:$nonce:$ncS:$cnonce:$qop:$ha2") else md5("$ha1:$nonce:$ha2")
        val sb = StringBuilder()
        sb.append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$resp\"")
        if (qop != null) sb.append(", qop=$qop, nc=$ncS, cnonce=\"$cnonce\"")
        if (opaque != null) sb.append(", opaque=\"$opaque\"")
        if (algorithm != null) sb.append(", algorithm=$algorithm")
        return req.newBuilder().header("Authorization", sb.toString()).build()
    }

    private fun parse(s: String): Map<String, String> {
        val m = HashMap<String, String>()
        Regex("(\\w+)\\s*=\\s*(\"([^\"]*)\"|[^,\\s]*)").findAll(s).forEach {
            val v = it.groupValues[3].ifEmpty { it.groupValues[2] }
            m[it.groupValues[1].lowercase()] = v
        }
        return m
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

object Hik {
    /** Cambia el códec de un canal (101 principal, 102 sub-stream) vía ISAPI. Devuelve null si salió bien. */
    fun setCodec(cam: Camera, cfg: AppConfig, channel: Int, codec: String): String? {
        val url = cam.httpBase() + "/ISAPI/Streaming/channels/$channel"
        val c = client(cam, cfg)
        val xml = c.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) return "lectura HTTP ${r.code}"
            r.body?.string() ?: return "sin respuesta"
        }
        val tag = Regex("<videoCodecType>[^<]*</videoCodecType>")
        if (!tag.containsMatchIn(xml)) return "no soporta cambio de códec"
        val nuevo = xml.replace(tag, "<videoCodecType>$codec</videoCodecType>")
        val body = nuevo.toRequestBody("application/xml; charset=utf-8".toMediaType())
        c.newCall(Request.Builder().url(url).put(body).build()).execute().use { r ->
            return if (r.isSuccessful) null else "HTTP ${r.code}: ${r.body?.string()?.let { Regex("<subStatusCode>([^<]+)").find(it)?.groupValues?.get(1) } ?: ""}"
        }
    }

    /** Aplica H.265 a principal y sub-stream de todas las cámaras activas. */
    fun applyH265All() {
        Thread {
            val cfg = AppState.config
            val res = StringBuilder()
            cfg.cameras.forEachIndexed { i, cam ->
                if (!cam.isConfigured || cam.ip.isBlank()) return@forEachIndexed
                AppState.codecStatus = "Aplicando H.265 a ${cam.label(i)}…"; AppState.changed()
                val errs = listOf(101, 102).mapNotNull { ch ->
                    try { setCodec(cam, cfg, ch, "H.265")?.let { "canal $ch: $it" } } catch (e: Exception) { "canal $ch: ${e.message}" }
                }
                res.append("${i + 1}. ${cam.label(i)}: ${if (errs.isEmpty()) "OK" else errs.joinToString("; ")}\n")
            }
            AppState.codecStatus = res.toString().ifBlank { "No hay cámaras activas" }
            AppState.update { } // reinicia el mosaico con el nuevo códec
        }.start()
    }

    fun client(cam: Camera, cfg: AppConfig, readTimeoutSec: Long = 20): OkHttpClient =
        OkHttpClient.Builder()
            .authenticator(DigestAuthenticator(cam.effUser(cfg), cam.effPass(cfg)))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .build()

    /** Captura JPG del canal principal. */
    fun snapshot(cam: Camera, cfg: AppConfig): ByteArray? {
        val req = Request.Builder().url(cam.httpBase() + "/ISAPI/Streaming/channels/101/picture").build()
        client(cam, cfg).newCall(req).execute().use { r ->
            return if (r.isSuccessful) r.body?.bytes() else null
        }
    }
}

/**
 * Escucha el canal de eventos de la cámara (/ISAPI/Event/notification/alertStream).
 * Requiere que en la cámara la detección de movimiento tenga marcado
 * "Notificar al centro de vigilancia / Notify Surveillance Center".
 */
class EventMonitor(
    private val index: Int,
    private val cam: Camera,
    private val cfg: AppConfig,
    private val onEvent: (Int, String) -> Unit
) : Thread("eventos-$index") {
    @Volatile private var running = true
    @Volatile private var call: Call? = null
    private val allowed = cfg.allowedEvents()
    private val typeXml = Regex("<eventType>\\s*([^<\\s]+)\\s*</eventType>", RegexOption.IGNORE_CASE)
    private val stateXml = Regex("<eventState>\\s*([^<\\s]+)\\s*</eventState>", RegexOption.IGNORE_CASE)
    private val typeJson = Regex("\"eventType\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val stateJson = Regex("\"eventState\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    override fun run() {
        val client = Hik.client(cam, cfg, readTimeoutSec = 180)
        while (running) {
            try {
                val req = Request.Builder().url(cam.httpBase() + "/ISAPI/Event/notification/alertStream").build()
                val c = client.newCall(req)
                call = c
                c.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        AppState.setCamStatus(index, if (resp.code == 401) "Eventos: usuario/clave incorrectos" else "Eventos: HTTP ${resp.code}")
                        return@use
                    }
                    AppState.setCamStatus(index, "")
                    val src = resp.body!!.source()
                    val buf = StringBuilder()
                    while (running) {
                        val line = src.readUtf8Line() ?: break
                        if (line.startsWith("--")) { handle(buf); buf.setLength(0); continue }
                        buf.append(line).append('\n')
                        if (line.contains("</EventNotificationAlert>", true)) { handle(buf); buf.setLength(0) }
                        if (buf.length > 100_000) buf.setLength(0)
                    }
                }
            } catch (e: Exception) {
                if (!running) break
            }
            if (running) try { sleep(10_000) } catch (e: InterruptedException) { break }
        }
    }

    private fun handle(buf: StringBuilder) {
        if (buf.isEmpty()) return
        val s = buf.toString()
        val type = (typeXml.find(s) ?: typeJson.find(s))?.groupValues?.get(1) ?: return
        val state = (stateXml.find(s) ?: stateJson.find(s))?.groupValues?.get(1) ?: "active"
        if (state.equals("active", true) && type.lowercase() in allowed) onEvent(index, type)
    }

    fun shutdown() {
        running = false
        call?.cancel()
        interrupt()
    }
}
