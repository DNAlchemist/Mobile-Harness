package com.jarves.mh.auth

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

internal class ChatGptLoopbackListener : AutoCloseable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 500 }
    val redirectUri: String = "http://127.0.0.1:${server.localPort}/auth/callback"

    suspend fun awaitCallback(attempt: OAuthAttempt): OAuthCallback {
        val deadline = System.nanoTime() + 5 * 60 * 1_000_000_000L
        while (System.nanoTime() < deadline) {
            currentCoroutineContext().ensureActive()
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            socket.use {
                socket.soTimeout = 3000
                val line = try { readRequestLine(socket.getInputStream()) } catch (_: Exception) { "" }
                val pieces = line.split(' ')
                if (pieces.size != 3 || pieces[0] != "GET") {
                    respond(socket, 400, "Invalid callback request.")
                    return@use
                }
                val target = pieces[1]
                if (!target.startsWith("/auth/callback?") && target != "/auth/callback") {
                    respond(socket, 404, "Not found.")
                    return@use
                }
                val result = try { ChatGptOAuth.callback(target, attempt) } catch (failure: ChatGptAuthException) {
                    respond(socket, 400, "Sign-in was not completed. Return to Mobile Harness and try again.")
                    throw failure
                }
                respond(socket, 200, "Authorization received. Return to Mobile Harness to finish connecting.")
                return result
            }
        }
        throw ChatGptAuthException("ChatGPT sign-in timed out. Continue with ChatGPT to try again.")
    }

    private fun readRequestLine(stream: java.io.InputStream): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() < 16_384) {
            val next = stream.read()
            if (next == -1 || next == 10) return bytes.toString("US-ASCII").trimEnd('\r')
            bytes.write(next)
        }
        throw ChatGptAuthException("Oversized sign-in callback.")
    }

    private fun respond(socket: java.net.Socket, status: Int, message: String) {
        val body = "<!doctype html><meta charset=utf-8><title>Mobile Harness</title><p>$message</p>".toByteArray(Charsets.UTF_8)
        runCatching { socket.getOutputStream().use { output ->
            output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.write(body)
        } }
    }

    override fun close() { runCatching { server.close() } }
}
