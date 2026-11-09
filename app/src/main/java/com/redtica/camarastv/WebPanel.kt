package com.redtica.camarastv

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONObject

/** Panel de configuración: abrir http://IP-DEL-TV:8080 desde el celular o la PC (misma red). */
class WebPanel(private val ctx: Context, port: Int) : NanoHTTPD(port) {

    override fun serve(s: IHTTPSession): Response {
        return try { route(s) } catch (e: Exception) {
            NanoHTTPD.newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8", "Error: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun route(s: IHTTPSession): Response {
        if (s.method == Method.POST) s.parseBody(HashMap())
        val p: Map<String, String> = s.parms
        val cfg = AppState.config
        val authed = s.cookies.read("pin") == cfg.pin

        if (s.uri == "/login" && s.method == Method.POST) {
            return if (p["pin"]?.trim() == cfg.pin) {
                s.cookies.set("pin", cfg.pin, 30)
                redirect("/")
            } else html(loginPage("PIN incorrecto"))
        }
        if (!authed) return html(loginPage(null))

        return when (s.uri) {
            "/save" -> { save(p); redirect("/?ok=1") }
            "/drive/link" -> {
                AppState.update(changeConfig = false) {
                    p["driveClientId"]?.let { v -> if (v.isNotBlank()) it.driveClientId = v.trim() }
                    p["driveClientSecret"]?.let { v -> if (v.isNotBlank()) it.driveClientSecret = v.trim() }
                }
                Drive.startLink(); redirect("/#drive")
            }
            "/drive/unlink" -> { Drive.unlink(); redirect("/#drive") }
            "/h265" -> { Hik.applyH265All(); redirect("/#codec") }
            "/mantenimiento" -> { AppState.maintenanceRequest = true; AppState.changed(); redirect("/#kiosco") }
            "/quitar-kiosco" -> {
                AppState.update(changeConfig = false) { it.kiosk = false }
                AppState.removeOwnerRequest = true; AppState.changed(); redirect("/#kiosco")
            }
            "/status" -> NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json", JSONObject()
                .put("drive", AppState.driveStatus).put("code", AppState.driveUserCode)
                .put("pending", AppState.pendingFiles).put("recording", AppState.recording.size)
                .put("lastEvent", AppState.lastEvent).toString())
            else -> html(mainPage(p["ok"] == "1"))
        }
    }

    private fun save(p: Map<String, String>) {
        AppState.update { c ->
            c.siteName = p["siteName"]?.trim()?.ifBlank { "Mi sitio" } ?: c.siteName
            c.defaultUser = p["defaultUser"]?.trim() ?: c.defaultUser
            p["defaultPass"]?.let { if (it.isNotEmpty()) c.defaultPass = it }
            c.gridSize = p["gridSize"]?.toIntOrNull()?.takeIf { it in listOf(1, 4, 9, 16) } ?: c.gridSize
            c.rotateSeconds = p["rotateSeconds"]?.toIntOrNull()?.coerceIn(0, 3600) ?: c.rotateSeconds
            c.recordEnabled = p.containsKey("recordEnabled")
            c.recordMain = p["recordMain"] == "1"
            c.clipSeconds = p["clipSeconds"]?.toIntOrNull()?.coerceIn(5, 300) ?: c.clipSeconds
            c.eventTypes = p["eventTypes"]?.trim()?.ifBlank { c.eventTypes } ?: c.eventTypes
            c.retentionDays = p["retentionDays"]?.toIntOrNull()?.coerceIn(0, 3650) ?: c.retentionDays
            c.localMaxMB = p["localMaxMB"]?.toIntOrNull()?.coerceIn(200, 100_000) ?: c.localMaxMB
            p["newPin"]?.trim()?.let { if (it.length >= 4) c.pin = it }
            c.kiosk = p.containsKey("kiosk")
            for (i in 0 until MAX_CAMS) {
                val cam = c.cameras[i]
                cam.enabled = p.containsKey("c${i}_en")
                cam.events = p.containsKey("c${i}_ev")
                cam.name = p["c${i}_name"]?.trim() ?: cam.name
                cam.ip = p["c${i}_ip"]?.trim() ?: cam.ip
                cam.user = p["c${i}_user"]?.trim() ?: cam.user
                p["c${i}_pass"]?.let { if (it.isNotEmpty()) cam.pass = if (it == "-") "" else it }
                cam.rtspPort = p["c${i}_rtsp"]?.toIntOrNull() ?: cam.rtspPort
                cam.httpPort = p["c${i}_http"]?.toIntOrNull() ?: cam.httpPort
                cam.customUrl = p["c${i}_url"]?.trim() ?: cam.customUrl
            }
        }
    }

    // ---------------- HTML ----------------
    private fun e(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private val css = """
        <meta name=viewport content="width=device-width,initial-scale=1">
        <style>
        body{font-family:system-ui,Arial;margin:0;background:#0f1722;color:#e6edf5}
        header{background:#13314f;padding:14px 16px;font-size:18px;font-weight:600}
        main{padding:12px;max-width:900px;margin:auto}
        .card{background:#18222f;border-radius:10px;padding:12px;margin:10px 0}
        h3{margin:4px 0 10px;font-size:15px;color:#7fc0ff}
        label{display:block;font-size:12px;color:#9fb0c3;margin-top:8px}
        input,select{width:100%;box-sizing:border-box;padding:9px;border-radius:6px;border:1px solid #2c3b4e;background:#0f1722;color:#fff;font-size:15px}
        input[type=checkbox]{width:auto;transform:scale(1.3);margin-right:8px}
        .row{display:flex;gap:8px}.row>div{flex:1}
        .chk{display:flex;align-items:center;margin-top:10px;font-size:14px}
        button{background:#2f7de1;color:#fff;border:0;border-radius:8px;padding:12px 16px;font-size:15px;width:100%;margin-top:10px}
        .sec{background:#3a4657}.ok{background:#1f6b3a;padding:10px;border-radius:8px}
        .code{font-size:32px;letter-spacing:4px;font-weight:700;color:#fff;text-align:center;padding:10px}
        details summary{cursor:pointer;color:#9fb0c3;font-size:13px;margin-top:8px}
        .muted{color:#9fb0c3;font-size:12px}
        </style>"""

    private fun loginPage(err: String?) = """<!doctype html><html><head><meta charset=utf-8>$css<title>Cámaras TV</title></head>
        <body><header>Cámaras TV · Acceso</header><main><div class=card>
        <form method=post action=/login accept-charset=utf-8>
        <label>PIN de administración (se muestra en el TV con el botón MENÚ)</label>
        <input name=pin type=password inputmode=numeric autofocus>
        ${if (err != null) "<p style='color:#ff8080'>$err</p>" else ""}
        <button>Entrar</button></form></div></main></body></html>"""

    private fun mainPage(saved: Boolean): String {
        val c = AppState.config
        val sb = StringBuilder()
        sb.append("""<!doctype html><html><head><meta charset=utf-8>$css<title>Cámaras TV</title></head><body>
            <header>Cámaras TV · ${e(c.siteName)}</header><main>""")
        if (saved) sb.append("<div class=ok>Configuración guardada. El TV se actualizó.</div>")

        // Drive
        sb.append("<div class=card id=drive><h3>Respaldo en Google Drive</h3>")
        sb.append("<p>${e(AppState.driveStatus)}<br><span class=muted>Archivos pendientes de subir: ${AppState.pendingFiles}</span></p>")
        if (AppState.driveUserCode.isNotEmpty()) {
            sb.append("""<p>1. Abra <a style="color:#7fc0ff" href="${e(AppState.driveVerifyUrl)}" target=_blank>${e(AppState.driveVerifyUrl)}</a>
                con la cuenta de Google donde quiere los respaldos.<br>2. Escriba este código:</p>
                <div class=code>${e(AppState.driveUserCode)}</div>
                <p class=muted>Esta página se actualiza sola.</p><meta http-equiv=refresh content=6>""")
        }
        if (c.driveLinked) {
            sb.append("<form method=post action=/drive/unlink><button class=sec>Desvincular Google Drive</button></form>")
        } else {
            sb.append("""<form method=post action=/drive/link accept-charset=utf-8>
                <label>Client ID (Google Cloud · tipo "TV y dispositivos de entrada limitada")</label>
                <input name=driveClientId value="${e(c.driveClientId)}">
                <label>Client Secret</label>
                <input name=driveClientSecret type=password placeholder="${if (c.driveClientSecret.isNotBlank()) "(guardado)" else ""}">
                <button>Vincular Google Drive</button></form>""")
        }
        sb.append("</div>")

        // Códec y kiosco
        val owner = Kiosk.isOwner(ctx)
        sb.append("""<div class=card id=codec><h3>Códec H.265</h3>
            <p class=muted>Cambia el stream principal y el sub-stream de todas las cámaras activas a H.265.
            La cámara se reinicia unos segundos.</p>
            ${if (AppState.codecStatus.isNotEmpty()) "<pre style='white-space:pre-wrap'>${e(AppState.codecStatus)}</pre>" else ""}
            <form method=post action=/h265><button>Aplicar H.265 a todas las cámaras</button></form></div>
            <div class=card id=kiosco><h3>Modo kiosco</h3>
            <p>${if (owner) "Bloqueo total ACTIVO (Device Owner)." else "Bloqueo básico: la app es la pantalla de inicio. Para bloqueo total vea la guía (comando ADB)."}</p>
            <form method=post action=/mantenimiento><button class=sec>Abrir Ajustes de Android (mantenimiento)</button></form>
            ${if (owner) "<form method=post action=/quitar-kiosco onsubmit=\"return confirm('¿Quitar el modo kiosco por completo?')\"><button class=sec>Quitar modo kiosco por completo</button></form>" else ""}
            </div>""")

        // General
        sb.append("""<form method=post action=/save accept-charset=utf-8>
            <div class=card><h3>General</h3>
            <label>Nombre del sitio (también es el nombre de la carpeta en Drive)</label>
            <input name=siteName value="${e(c.siteName)}">
            <div class=row><div><label>Usuario por defecto de las cámaras</label><input name=defaultUser value="${e(c.defaultUser)}"></div>
            <div><label>Contraseña por defecto</label><input name=defaultPass type=password placeholder="${if (c.defaultPass.isNotEmpty()) "(sin cambios)" else ""}"></div></div>
            <div class=row><div><label>Mosaico en el TV</label><select name=gridSize>
            ${listOf(1, 4, 9, 16).joinToString("") { "<option value=$it ${if (c.gridSize == it) "selected" else ""}>$it cámaras</option>" }}
            </select></div>
            <div><label>Rotar páginas cada (seg, 0 = no)</label><input name=rotateSeconds type=number value=${c.rotateSeconds}></div></div>
            <div class=chk><input type=checkbox name=kiosk ${if (c.kiosk) "checked" else ""}>Modo kiosco (el TV solo muestra esta app)</div>
            </div>
            <div class=card><h3>Grabación por eventos</h3>
            <div class=chk><input type=checkbox name=recordEnabled ${if (c.recordEnabled) "checked" else ""}>Grabar clips cuando hay movimiento</div>
            <div class=row><div><label>Duración del clip (seg)</label><input name=clipSeconds type=number value=${c.clipSeconds}></div>
            <div><label>Calidad del clip</label><select name=recordMain>
            <option value=0 ${if (!c.recordMain) "selected" else ""}>Sub-stream (liviano)</option>
            <option value=1 ${if (c.recordMain) "selected" else ""}>Principal (alta calidad)</option></select></div></div>
            <div class=row><div><label>Borrar de Drive después de (días)</label><input name=retentionDays type=number value=${c.retentionDays}></div>
            <div><label>Máximo en el TV si no hay Internet (MB)</label><input name=localMaxMB type=number value=${c.localMaxMB}></div></div>
            <label>Tipos de evento (Hikvision)</label><input name=eventTypes value="${e(c.eventTypes)}">
            <p class=muted>VMD = movimiento · fielddetection = intrusión · linedetection = cruce de línea</p>
            </div>""")

        // Cámaras
        c.cameras.forEachIndexed { i, cam ->
            sb.append("""<div class=card><h3>Cámara ${i + 1}</h3>
                <div class=row><div class=chk><input type=checkbox name=c${i}_en ${if (cam.enabled) "checked" else ""}>Activa</div>
                <div class=chk><input type=checkbox name=c${i}_ev ${if (cam.events) "checked" else ""}>Grabar eventos</div></div>
                <div class=row><div><label>Nombre</label><input name=c${i}_name value="${e(cam.name)}" placeholder="Entrada, Parqueo..."></div>
                <div><label>IP</label><input name=c${i}_ip value="${e(cam.ip)}" placeholder="192.168.1.64" inputmode=decimal></div></div>
                <details><summary>Usuario, clave y puertos (si son distintos a los por defecto)</summary>
                <div class=row><div><label>Usuario</label><input name=c${i}_user value="${e(cam.user)}"></div>
                <div><label>Contraseña ("-" para borrar)</label><input name=c${i}_pass type=password placeholder="${if (cam.pass.isNotEmpty()) "(guardada)" else "por defecto"}"></div></div>
                <div class=row><div><label>Puerto RTSP</label><input name=c${i}_rtsp type=number value=${cam.rtspPort}></div>
                <div><label>Puerto HTTP</label><input name=c${i}_http type=number value=${cam.httpPort}></div></div>
                <label>URL RTSP personalizada (solo otras marcas)</label><input name=c${i}_url value="${e(cam.customUrl)}">
                </details></div>""")
        }

        sb.append("""<div class=card><h3>Seguridad</h3>
            <label>Nuevo PIN del panel (mínimo 4 dígitos, vacío = sin cambio)</label><input name=newPin inputmode=numeric></div>
            <button>Guardar configuración</button></form>
            <p class=muted>IP del TV: ${AppState.localIp()}:8080</p></main></body></html>""")
        return sb.toString()
    }

    private fun html(body: String) = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", body)

    private fun redirect(to: String) = NanoHTTPD.newFixedLengthResponse(Response.Status.REDIRECT_SEE_OTHER, "text/plain", "").apply {
        addHeader("Location", to)
    }
}
