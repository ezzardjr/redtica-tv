package com.redtica.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** [mcast] = dirección multicast udp://239.10.10.x:1234 si el canal la tiene, o null. */
/** [num] = número que ve el cliente (assets/orden.txt). */
data class Channel(
    val name: String,
    val url: String,
    val id: String? = null,
    var mcast: String? = null,
    var num: Int = 0
)

/** Descarga y lee la lista M3U del Astra. */
object M3uParser {

    fun fetch(playlistUrl: String): List<Channel> {
        val conn = URL(playlistUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(text, playlistUrl)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(text: String, base: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var pendingName: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> pendingName = nameOf(line)
                line.startsWith("#") -> Unit
                else -> {
                    val url = URL(URL(base), line).toString()
                    val id = Regex("/play/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
                    out += Channel(pendingName ?: "Canal ${out.size + 1}", url, id)
                    pendingName = null
                }
            }
        }
        return out
    }

    /** El nombre va después de la primera coma que no esté dentro de comillas. */
    private fun nameOf(extinf: String): String? {
        var inQuotes = false
        for (i in extinf.indices) {
            when (extinf[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return extinf.substring(i + 1).trim().ifEmpty { null }
            }
        }
        return null
    }

    /** Lee assets/multicast.txt (id:octeto,...) y asigna la dirección multicast a cada canal. */
    fun applyMulticast(channels: List<Channel>, mapText: String) {
        val map = mapText.lineSequence().filter { !it.startsWith("#") }.joinToString(",")
            .split(',').mapNotNull { e ->
                val p = e.trim().split(':')
                if (p.size == 2 && p[1].toIntOrNull() != null) p[0] to p[1].toInt() else null
            }.toMap()
        channels.forEach { ch -> ch.id?.let { map[it] }?.let { ch.mcast = "udp://239.10.10.$it:1234" } }
    }

    /**
     * Ordena según assets/orden.txt (numero|id|nombre) y asigna el número de canal.
     * Los canales del Astra que no estén en el archivo van al final, en su orden original.
     */
    fun applyOrder(channels: List<Channel>, orderText: String): List<Channel> {
        val order = orderText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { l ->
                val p = l.split('|')
                val n = p.getOrNull(0)?.trim()?.toIntOrNull()
                val id = p.getOrNull(1)?.trim()
                if (n != null && !id.isNullOrEmpty()) id to n else null
            }.toMap()
        val known = channels.filter { it.id != null && order.containsKey(it.id) }
            .onEach { it.num = order.getValue(it.id!!) }
            .sortedBy { it.num }
        var next = (known.maxOfOrNull { it.num } ?: 0) + 1
        val rest = channels.filter { it.id == null || !order.containsKey(it.id) }
            .onEach { it.num = next++ }
        return known + rest
    }
}
