package com.redtica.tv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Channel(val name: String, val url: String)

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
                    out += Channel(pendingName ?: "Canal ${out.size + 1}", url)
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
}
