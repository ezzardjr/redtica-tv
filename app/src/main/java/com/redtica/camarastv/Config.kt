package com.redtica.camarastv

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

const val MAX_CAMS = 16

data class Camera(
    var name: String = "",
    var ip: String = "",
    var rtspPort: Int = 554,
    var httpPort: Int = 80,
    var user: String = "",
    var pass: String = "",
    var enabled: Boolean = false,
    var events: Boolean = true,
    var customUrl: String = ""
) {
    fun label(i: Int) = name.ifBlank { "Cámara ${i + 1}" }
    fun effUser(c: AppConfig) = user.ifBlank { c.defaultUser }
    fun effPass(c: AppConfig) = pass.ifBlank { c.defaultPass }
    val isConfigured get() = enabled && (ip.isNotBlank() || customUrl.isNotBlank())
    val canEvents get() = isConfigured && events && ip.isNotBlank()

    /** Hikvision: 101 = principal, 102 = sub-stream. Las credenciales van como opciones de VLC. */
    fun rtspUrl(main: Boolean): String {
        if (customUrl.isNotBlank()) return customUrl.trim()
        val ch = if (main) "101" else "102"
        return "rtsp://${ip.trim()}:$rtspPort/Streaming/Channels/$ch"
    }

    fun httpBase() = "http://${ip.trim()}:$httpPort"

    fun toJson() = JSONObject().apply {
        put("name", name); put("ip", ip); put("rtspPort", rtspPort); put("httpPort", httpPort)
        put("user", user); put("pass", pass); put("enabled", enabled); put("events", events)
        put("customUrl", customUrl)
    }

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            o.optString("name"), o.optString("ip"), o.optInt("rtspPort", 554), o.optInt("httpPort", 80),
            o.optString("user"), o.optString("pass"), o.optBoolean("enabled"), o.optBoolean("events", true),
            o.optString("customUrl")
        )
    }
}

data class AppConfig(
    var siteName: String = "Mi sitio",
    var defaultUser: String = "admin",
    var defaultPass: String = "",
    var gridSize: Int = 16,
    var rotateSeconds: Int = 0,
    var recordEnabled: Boolean = true,
    var recordMain: Boolean = false,
    var clipSeconds: Int = 30,
    var eventTypes: String = "VMD,fielddetection,linedetection,regionEntrance",
    var retentionDays: Int = 0,
    var localMaxMB: Int = 3000,
    var driveClientId: String = "",
    var driveClientSecret: String = "",
    var driveRefreshToken: String = "",
    var driveRootId: String = "",
    var driveRootName: String = "",
    var pin: String = "",
    var kiosk: Boolean = true,
    var driveMaxGB: Int = 1000,
    var cameras: MutableList<Camera> = MutableList(MAX_CAMS) { Camera() }
) {
    fun allowedEvents(): Set<String> =
        eventTypes.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    val driveLinked get() = driveRefreshToken.isNotBlank()
    /** Credenciales de Google: las del panel, o las que vienen incluidas en el APK. */
    val clientId get() = driveClientId.ifBlank { BuildConfig.GOOGLE_CLIENT_ID }
    val clientSecret get() = driveClientSecret.ifBlank { BuildConfig.GOOGLE_CLIENT_SECRET }

    fun toJson() = JSONObject().apply {
        put("siteName", siteName); put("defaultUser", defaultUser); put("defaultPass", defaultPass)
        put("gridSize", gridSize); put("rotateSeconds", rotateSeconds)
        put("recordEnabled", recordEnabled); put("recordMain", recordMain); put("clipSeconds", clipSeconds)
        put("eventTypes", eventTypes); put("retentionDays", retentionDays); put("localMaxMB", localMaxMB); put("driveMaxGB", driveMaxGB)
        put("driveClientId", driveClientId); put("driveClientSecret", driveClientSecret)
        put("driveRefreshToken", driveRefreshToken); put("driveRootId", driveRootId)
        put("driveRootName", driveRootName); put("pin", pin); put("kiosk", kiosk)
        put("cameras", JSONArray().apply { cameras.forEach { put(it.toJson()) } })
    }

    fun copyDeep(): AppConfig = fromJson(toJson())

    companion object {
        fun fromJson(o: JSONObject): AppConfig {
            val c = AppConfig(
                o.optString("siteName", "Mi sitio"), o.optString("defaultUser", "admin"),
                o.optString("defaultPass"), o.optInt("gridSize", 16), o.optInt("rotateSeconds", 0),
                o.optBoolean("recordEnabled", true), o.optBoolean("recordMain", false),
                o.optInt("clipSeconds", 30),
                o.optString("eventTypes", "VMD,fielddetection,linedetection,regionEntrance"),
                o.optInt("retentionDays", 0), o.optInt("localMaxMB", 3000),
                o.optString("driveClientId"), o.optString("driveClientSecret"),
                o.optString("driveRefreshToken"), o.optString("driveRootId"),
                o.optString("driveRootName"), o.optString("pin"), o.optBoolean("kiosk", true)
            )
            c.driveMaxGB = o.optInt("driveMaxGB", 1000)
            val arr = o.optJSONArray("cameras")
            c.cameras = MutableList(MAX_CAMS) { i ->
                arr?.optJSONObject(i)?.let { Camera.fromJson(it) } ?: Camera()
            }
            return c
        }
    }
}

/** Estado compartido entre la pantalla, el servicio y el panel web (mismo proceso). */
object AppState {
    private const val PREFS = "camarastv"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val configListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var config: AppConfig = AppConfig()
        private set
    private lateinit var appCtx: Context

    val recording: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val camStatus = ConcurrentHashMap<Int, String>()
    @Volatile var driveStatus: String = "Google Drive no vinculado"
    @Volatile var driveUserCode: String = ""
    @Volatile var driveVerifyUrl: String = ""
    @Volatile var pendingFiles: Int = 0
    @Volatile var lastEvent: String = ""
    @Volatile var codecStatus: String = ""
    @Volatile var driveUsage: String = ""
    @Volatile var maintenanceRequest: Boolean = false
    @Volatile var removeOwnerRequest: Boolean = false

    fun init(ctx: Context) {
        if (::appCtx.isInitialized) return
        appCtx = ctx.applicationContext
        val sp = appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = sp.getString("config", null)
        config = try { raw?.let { AppConfig.fromJson(JSONObject(it)) } ?: AppConfig() } catch (e: Exception) { AppConfig() }
        if (config.pin.isBlank()) {
            config.pin = (100000..999999).random().toString()
            persist()
        }
        if (config.driveLinked) driveStatus = "Google Drive vinculado"
    }

    @Synchronized
    fun update(changeConfig: Boolean = true, block: (AppConfig) -> Unit) {
        val c = config.copyDeep()
        block(c)
        config = c
        persist()
        if (changeConfig) main.post { configListeners.forEach { it() } }
        changed()
    }

    private fun persist() {
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("config", config.toJson().toString()).apply()
    }

    fun changed() = main.post { listeners.forEach { it() } }
    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    fun addConfigListener(l: () -> Unit) = configListeners.add(l)
    fun removeConfigListener(l: () -> Unit) = configListeners.remove(l)

    fun setCamStatus(i: Int, s: String) {
        if (s.isEmpty()) camStatus.remove(i) else camStatus[i] = s
        changed()
    }

    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()?.hostAddress ?: "sin red"
    } catch (e: Exception) { "sin red" }
}
