package com.redtica.camarastv

import android.content.Context
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Google Drive vía OAuth "para TV y dispositivos de entrada limitada" (device flow),
 * con alcance drive.file: la app solo ve y administra los archivos que ella misma crea.
 */
object Drive {
    private const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    private const val FOLDER = "application/vnd.google-apps.folder"
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    @Volatile private var accessToken: String? = null
    @Volatile private var expiresAt = 0L
    @Volatile private var linking = false
    private val folderCache = HashMap<String, String>()

    class AuthException(msg: String) : Exception(msg)

    // ---------- Vinculación ----------
    fun startLink() {
        val cfg = AppState.config
        if (cfg.driveClientId.isBlank() || cfg.driveClientSecret.isBlank()) {
            AppState.driveStatus = "Falta el Client ID / Client Secret de Google"
            AppState.changed(); return
        }
        if (linking) return
        linking = true
        Thread {
            try {
                val r = post("https://oauth2.googleapis.com/device/code",
                    FormBody.Builder().add("client_id", cfg.driveClientId).add("scope", SCOPE).build())
                val deviceCode = r.getString("device_code")
                AppState.driveUserCode = r.getString("user_code")
                AppState.driveVerifyUrl = r.optString("verification_url", "https://www.google.com/device")
                AppState.driveStatus = "Esperando autorización en ${AppState.driveVerifyUrl}"
                AppState.changed()
                var interval = r.optLong("interval", 5)
                val deadline = System.currentTimeMillis() + r.optLong("expires_in", 1800) * 1000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(interval * 1000)
                    val t = postRaw("https://oauth2.googleapis.com/token", FormBody.Builder()
                        .add("client_id", cfg.driveClientId).add("client_secret", cfg.driveClientSecret)
                        .add("device_code", deviceCode)
                        .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code").build())
                    if (t.has("refresh_token")) {
                        accessToken = t.getString("access_token")
                        expiresAt = System.currentTimeMillis() + (t.optLong("expires_in", 3600) - 60) * 1000
                        synchronized(folderCache) { folderCache.clear() }
                        AppState.update(changeConfig = false) {
                            it.driveRefreshToken = t.getString("refresh_token")
                            it.driveRootId = ""; it.driveRootName = ""
                        }
                        AppState.driveUserCode = ""
                        AppState.driveStatus = "Google Drive vinculado"
                        AppState.changed()
                        return@Thread
                    }
                    when (t.optString("error")) {
                        "authorization_pending" -> {}
                        "slow_down" -> interval += 5
                        else -> throw Exception(t.optString("error_description", t.optString("error")))
                    }
                }
                throw Exception("el código expiró")
            } catch (e: Exception) {
                AppState.driveUserCode = ""
                AppState.driveStatus = "No se pudo vincular: ${e.message}"
                AppState.changed()
            } finally { linking = false }
        }.start()
    }

    fun unlink() {
        accessToken = null; expiresAt = 0
        synchronized(folderCache) { folderCache.clear() }
        AppState.update(changeConfig = false) { it.driveRefreshToken = ""; it.driveRootId = ""; it.driveRootName = "" }
        AppState.driveStatus = "Google Drive no vinculado"
        AppState.changed()
    }

    private fun token(): String {
        val cfg = AppState.config
        if (!cfg.driveLinked) throw AuthException("no vinculado")
        accessToken?.let { if (System.currentTimeMillis() < expiresAt) return it }
        val t = postRaw("https://oauth2.googleapis.com/token", FormBody.Builder()
            .add("client_id", cfg.driveClientId).add("client_secret", cfg.driveClientSecret)
            .add("refresh_token", cfg.driveRefreshToken).add("grant_type", "refresh_token").build())
        if (!t.has("access_token")) {
            if (t.optString("error") == "invalid_grant") {
                AppState.driveStatus = "El permiso de Google venció: vuelva a vincular Drive"
                AppState.update(changeConfig = false) { it.driveRefreshToken = "" }
            }
            throw AuthException(t.optString("error_description", t.optString("error", "token")))
        }
        accessToken = t.getString("access_token")
        expiresAt = System.currentTimeMillis() + (t.optLong("expires_in", 3600) - 60) * 1000
        return accessToken!!
    }

    // ---------- Carpetas y archivos ----------
    private fun rootFolder(): String {
        val cfg = AppState.config
        val name = "Cámaras - ${cfg.siteName}"
        if (cfg.driveRootId.isNotBlank() && cfg.driveRootName == name) return cfg.driveRootId
        val id = findOrCreate(name, null)
        AppState.update(changeConfig = false) { it.driveRootId = id; it.driveRootName = name }
        return id
    }

    private fun findOrCreate(name: String, parent: String?): String {
        val key = "${parent ?: "root"}/$name"
        synchronized(folderCache) { folderCache[key]?.let { return it } }
        val esc = name.replace("\\", "\\\\").replace("'", "\\'")
        var q = "name='$esc' and mimeType='$FOLDER' and trashed=false"
        if (parent != null) q += " and '$parent' in parents"
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", q).addQueryParameter("fields", "files(id,name)")
            .addQueryParameter("spaces", "drive").build()
        val found = get(url.toString()).optJSONArray("files")
        val id = if (found != null && found.length() > 0) found.getJSONObject(0).getString("id") else {
            val meta = JSONObject().put("name", name).put("mimeType", FOLDER)
            if (parent != null) meta.put("parents", org.json.JSONArray().put(parent))
            val req = Request.Builder().url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer ${token()}")
                .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            exec(req).getString("id")
        }
        synchronized(folderCache) { folderCache[key] = id }
        return id
    }

    /** Sube un archivo a  Cámaras - <sitio>/<aaaa-mm-dd>/  (subida reanudable). */
    fun upload(file: File) {
        val day = file.name.take(10).takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) }
            ?: SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(file.lastModified()))
        val folder = findOrCreate(day, rootFolder())
        val mime = if (file.name.endsWith(".jpg")) "image/jpeg" else "video/mp4"
        val meta = JSONObject().put("name", file.name).put("parents", org.json.JSONArray().put(folder))
        val init = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
            .header("Authorization", "Bearer ${token()}")
            .header("X-Upload-Content-Type", mime)
            .header("X-Upload-Content-Length", file.length().toString())
            .post(meta.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        val location = http.newCall(init).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Drive init ${r.code}: ${r.body?.string()?.take(200)}")
            r.header("Location") ?: throw Exception("Drive sin URL de subida")
        }
        val put = Request.Builder().url(location).put(file.asRequestBody(mime.toMediaType())).build()
        http.newCall(put).execute().use { r ->
            if (!r.isSuccessful) throw Exception("Drive subida ${r.code}")
        }
    }

    /** Borra de Drive las carpetas de días más antiguas que la retención configurada. */
    fun purgeOld(days: Int) {
        if (days <= 0) return
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        val limit = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        val root = rootFolder()
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "'$root' in parents and mimeType='$FOLDER' and trashed=false")
            .addQueryParameter("fields", "files(id,name)").addQueryParameter("pageSize", "1000").build()
        val files = get(url.toString()).optJSONArray("files") ?: return
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            val name = f.getString("name")
            if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(name) && name < limit) {
                val del = Request.Builder().url("https://www.googleapis.com/drive/v3/files/${f.getString("id")}")
                    .header("Authorization", "Bearer ${token()}").delete().build()
                http.newCall(del).execute().close()
                synchronized(folderCache) { folderCache.remove("$root/$name") }
            }
        }
    }

    // ---------- HTTP ----------
    private fun get(url: String): JSONObject =
        exec(Request.Builder().url(url).header("Authorization", "Bearer ${token()}").build())

    private fun exec(req: Request): JSONObject = http.newCall(req).execute().use { r ->
        val body = r.body?.string() ?: ""
        if (r.code == 401) { accessToken = null; throw AuthException("401") }
        if (!r.isSuccessful) throw Exception("Drive ${r.code}: ${body.take(200)}")
        JSONObject(body.ifBlank { "{}" })
    }

    private fun post(url: String, body: FormBody): JSONObject {
        val o = postRaw(url, body)
        if (o.has("error")) throw Exception(o.optString("error_description", o.optString("error")))
        return o
    }

    private fun postRaw(url: String, body: FormBody): JSONObject =
        http.newCall(Request.Builder().url(url).post(body).build()).execute().use { r ->
            JSONObject(r.body?.string()?.ifBlank { "{}" } ?: "{}")
        }
}

/** Cola de subida: toma lo que hay en "pendientes", lo sube y libera espacio local. */
class Uploader(private val ctx: Context) : Thread("subidas") {
    @Volatile private var running = true
    private val lock = Object()
    private var lastPurge = 0L

    fun kick() = synchronized(lock) { lock.notifyAll() }

    override fun run() {
        while (running) {
            try { cycle() } catch (_: Exception) { }
            synchronized(lock) { try { lock.wait(30_000) } catch (_: InterruptedException) { } }
        }
    }

    private fun cycle() {
        val cfg = AppState.config
        val dir = Recorder.pendingDir(ctx)
        val files = (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && !it.name.endsWith(".part") }
            .sortedBy { it.name }
        AppState.pendingFiles = files.size

        // Tope de espacio local: si no hay Internet/Drive, se borran los más viejos.
        var total = files.sumOf { it.length() }
        val max = cfg.localMaxMB.toLong() * 1024 * 1024
        val queue = files.toMutableList()
        while (total > max && queue.isNotEmpty()) {
            val f = queue.removeAt(0); total -= f.length(); f.delete()
        }

        if (!cfg.driveLinked) { AppState.pendingFiles = queue.size; AppState.changed(); return }
        var ok = 0
        for (f in queue) {
            if (!running) break
            try {
                Drive.upload(f)
                f.delete(); ok++
                AppState.pendingFiles = queue.size - ok
                AppState.driveStatus = "Google Drive vinculado · última subida ${
                    SimpleDateFormat("HH:mm", Locale.US).format(Date())}"
                AppState.changed()
            } catch (e: Drive.AuthException) {
                AppState.changed(); return
            } catch (e: Exception) {
                AppState.driveStatus = "Error subiendo a Drive (se reintentará): ${e.message?.take(80)}"
                AppState.changed(); return
            }
        }
        if (System.currentTimeMillis() - lastPurge > 6 * 3600_000L) {
            try { Drive.purgeOld(cfg.retentionDays); lastPurge = System.currentTimeMillis() } catch (_: Exception) { }
        }
    }

    fun shutdown() { running = false; kick() }
}
