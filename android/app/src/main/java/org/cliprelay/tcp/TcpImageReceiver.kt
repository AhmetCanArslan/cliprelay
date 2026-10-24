package org.cliprelay.tcp

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [host] is the primary (LAN) address, kept for older peers.
 * [hosts] lists every address a sender may try, LAN first then Tailscale.
 */
data class ServerInfo(val host: String, val port: Int, val hosts: List<String> = listOf(host))

class TcpImageReceiver(
    private val expectedSize: Int,
    private val allowedSenderIp: String?,
    private val tcpNonce: ByteArray? = null,
    private val noConnectionTimeoutMs: Int = 30_000,
    private val transferTimeoutMs: Int = 120_000,
    private val maxConnections: Int = 4,
) {
    private val cancelled = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null

    fun start(): ServerInfo {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress("0.0.0.0", 0))
        serverSocket = server

        val hosts = NetworkUtil.getLocalIpAddresses()
        // The legacy single-host field stays the LAN address: old senders bind their
        // socket to the LAN interface, so a Tailscale address there is unreachable.
        val localIp = NetworkUtil.getLocalIpAddress() ?: hosts.firstOrNull() ?: "0.0.0.0"
        return ServerInfo(localIp, server.localPort, hosts.ifEmpty { listOf(localIp) })
    }

    /**
     * Blocks until [expectedSize] bytes are received from an allowed sender,
     * or throws [TcpTransferException] on error/timeout/cancel.
     */
    fun receive(): ByteArray {
        val server = serverSocket ?: throw TcpTransferException("Server not started")

        try {
            server.soTimeout = noConnectionTimeoutMs

            var attemptsLeft = maxConnections
            while (attemptsLeft > 0 && !cancelled.get()) {
                attemptsLeft--

                val client: Socket
                try {
                    client = server.accept()
                } catch (e: SocketTimeoutException) {
                    throw TcpTransferException("No connection within ${noConnectionTimeoutMs}ms", e)
                }

                try {
                    if (tcpNonce != null) {
                        // Nonce-based validation: read first 16 bytes and constant-time compare
                        client.soTimeout = transferTimeoutMs
                        val receivedNonce = try {
                            readExactly(client.getInputStream(), 16)
                        } catch (e: Exception) {
                            client.close()
                            continue
                        }
                        // Constant-time comparison
                        var mismatch: Int = 0
                        for (i in 0 until 16) {
                            mismatch = mismatch or (receivedNonce[i].toInt() xor tcpNonce[i].toInt())
                        }
                        if (mismatch != 0) {
                            client.close()
                            continue
                        }
                    } else {
                        // TODO(2026-05-01): Remove IP validation fallback — all clients should support tcpNonce by now
                        val remoteIp = (client.remoteSocketAddress as? InetSocketAddress)
                            ?.address?.hostAddress

                        if (allowedSenderIp != null && remoteIp != allowedSenderIp) {
                            client.close()
                            continue
                        }
                    }

                    client.soTimeout = transferTimeoutMs
                    val data = readExactly(client.getInputStream(), expectedSize)
                    return data
                } finally {
                    try { client.close() } catch (_: Exception) {}
                }
            }

            throw TcpTransferException("No valid connection after $maxConnections attempts")
        } catch (e: TcpTransferException) {
            throw e
        } catch (e: Exception) {
            if (cancelled.get()) {
                throw TcpTransferException("Transfer cancelled")
            }
            throw TcpTransferException("Receive failed: ${e.message}", e)
        }
    }

    fun cancel() {
        cancelled.set(true)
        close()
    }

    fun close() {
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            if (cancelled.get()) throw TcpTransferException("Transfer cancelled")
            val read = input.read(buffer, offset, size - offset)
            if (read == -1) {
                throw TcpTransferException(
                    "Stream closed after $offset bytes, expected $size"
                )
            }
            offset += read
        }
        return buffer
    }
}
