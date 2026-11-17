package com.redtica.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.UdpDataSource
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException

/**
 * Lector de canales multicast (udp://239.10.10.x:1234) con un búfer de recepción
 * grande. Android trae por defecto unos pocos cientos de KB: cuando el WiFi entrega
 * los paquetes en ráfagas, el sistema descarta los que no caben y la imagen se pega.
 * Con 8 MB caben varios segundos de video HD aunque la app tarde en leerlos.
 */
@UnstableApi
class BigUdpDataSource : BaseDataSource(/* isNetwork = */ true) {

    companion object {
        private const val RECEIVE_BUFFER = 8 * 1024 * 1024
        private const val MAX_PACKET = 2048
        private const val TIMEOUT_MS = 8000

        // Estadística para el panel de diagnóstico: paquetes TS que faltaron en la red
        @Volatile @JvmField var tsPackets = 0L
        @Volatile @JvmField var tsLost = 0L
        private val lastCc = IntArray(8192) { -1 }
        fun resetStats() { tsPackets = 0; tsLost = 0; java.util.Arrays.fill(lastCc, -1) }

        /** Revisa el contador de continuidad de cada paquete TS del datagrama. */
        fun checkContinuity(b: ByteArray, len: Int) {
            var i = len % 188   // salta una posible cabecera RTP
            while (i + 188 <= len) {
                if (b[i] == 0x47.toByte()) {
                    val pid = ((b[i + 1].toInt() and 0x1F) shl 8) or (b[i + 2].toInt() and 0xFF)
                    val afc = (b[i + 3].toInt() shr 4) and 3
                    val cc = b[i + 3].toInt() and 0x0F
                    tsPackets++
                    if (pid != 0x1FFF && (afc and 1) == 1) {
                        val last = lastCc[pid]
                        if (last >= 0 && cc != last && cc != ((last + 1) and 0x0F)) {
                            tsLost += ((cc - last - 1) and 0x0F).coerceAtLeast(1)
                        }
                        lastCc[pid] = cc
                    }
                }
                i += 188
            }
        }
    }

    private val packetBuffer = ByteArray(MAX_PACKET)
    private val packet = DatagramPacket(packetBuffer, 0, MAX_PACKET)
    private var socket: MulticastSocket? = null
    private var group: InetAddress? = null
    private var uri: Uri? = null
    private var opened = false
    private var remaining = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val host = dataSpec.uri.host ?: throw IOException("Dirección multicast vacía")
        val port = dataSpec.uri.port
        transferInitializing(dataSpec)
        try {
            val address = InetAddress.getByName(host)
            val s = MulticastSocket(null).apply {
                reuseAddress = true
                // Pedir el búfer grande antes de enlazar el socket
                receiveBufferSize = RECEIVE_BUFFER
                bind(InetSocketAddress(port))
                soTimeout = TIMEOUT_MS
            }
            if (address.isMulticastAddress) {
                s.joinGroup(address)
                group = address
            }
            socket = s
        } catch (e: IOException) {
            throw UdpDataSource.UdpDataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        }
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0) {
            try {
                socket!!.receive(packet)
            } catch (e: SocketTimeoutException) {
                throw UdpDataSource.UdpDataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
            } catch (e: IOException) {
                throw UdpDataSource.UdpDataSourceException(e, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            }
            remaining = packet.length
            checkContinuity(packetBuffer, packet.length)
            bytesTransferred(remaining)
        }
        val start = packet.length - remaining
        val n = minOf(remaining, length)
        System.arraycopy(packetBuffer, start, buffer, offset, n)
        remaining -= n
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        socket?.let { s ->
            group?.let { g -> try { s.leaveGroup(g) } catch (_: IOException) { } }
            s.close()
        }
        socket = null
        group = null
        remaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
    }
}
