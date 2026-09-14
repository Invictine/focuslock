package com.focuslock.app.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext

object TickTickOAuthLoopbackServer {
    private const val TAG = "TickTickLoopback"

    /**
     * Primary loopback port. TickTick's developer portal registers a FIXED redirect
     * URI (http://127.0.0.1:8080/ — see TickTickApiClient.REDIRECT_URI), so the
     * authorize URL must use whatever port is bound here; alternate ports only keep
     * the session alive (the manual "paste the redirect" path works with any port).
     */
    const val DEFAULT_PORT = 8080

    /** Ports tried in order when the default one is already taken. */
    private val CANDIDATE_PORTS = intArrayOf(8080, 8081, 8082, 8083, 8084, 8085)

    /** Max accepted connections while waiting for the ?code= request. */
    private const val MAX_ACCEPTS = 20

    /** Overall wall-clock budget for the whole accept loop. */
    private const val ACCEPT_BUDGET_MS = 60_000L

    /** Per-accept read timeout: preconnect sockets send nothing and are skipped. */
    private const val READ_TIMEOUT_MS = 10_000

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    var isRunning: Boolean = false
        private set

    /** Port of the most recent successful bind; [DEFAULT_PORT] when never bound. */
    @Volatile
    var boundPort: Int = DEFAULT_PORT
        private set

    /**
     * Binds the loopback listener on the first free candidate port (127.0.0.1 only).
     * A port conflict used to fail silently and kill the whole OAuth flow; now the
     * next candidate is tried and the caller learns the bound port so the authorize
     * URL's redirect_uri can match. Fast — does not wait for the redirect.
     * Returns the bound port, or null when every candidate port is taken.
     */
    suspend fun bind(): Int? = withContext(Dispatchers.IO) {
        stop() // Stop any previous instance
        for (port in CANDIDATE_PORTS) {
            try {
                // Backlog > 1: browsers open several parallel connections (preconnect)
                // while the loop is busy with one request.
                val server = ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"))
                server.soTimeout = READ_TIMEOUT_MS
                serverSocket = server
                isRunning = true
                boundPort = port
                Log.d(TAG, "OAuth loopback server listening on 127.0.0.1:$port")
                return@withContext port
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind OAuth loopback port $port", e)
            }
        }
        Log.w(TAG, "No OAuth loopback port available (tried ${CANDIDATE_PORTS.joinToString()})")
        null
    }

    /**
     * Waits for the OAuth redirect on the socket bound by [bind]. Loops accept()
     * until a request carrying `code=` arrives — a single accept() used to grab a
     * browser preconnect instead of the real redirect, losing the code entirely.
     * Requests without a code (favicon, robots.txt, empty preconnects) are answered
     * with a harmless page and skipped. Bounded by [MAX_ACCEPTS] accepts or
     * [ACCEPT_BUDGET_MS]. Returns true once [onCodeReceived] fired.
     *
     * Cancellation-safe: `accept()` runs under [runInterruptible] and the socket is
     * closed from the caller's completion handler, so cancelling the collecting
     * coroutine (e.g. the Settings screen being disposed) releases the port
     * immediately instead of leaking it.
     */
    suspend fun awaitCode(onCodeReceived: (code: String, state: String?) -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            val server = serverSocket
            if (server == null || !isRunning) return@withContext false

            // A plain ServerSocket.accept() ignores thread interrupts; closing the
            // socket is what actually unblocks it. The completion handler fires on
            // cancellation of the caller's coroutine, so the accept cannot outlive it.
            val closeOnCancellation = coroutineContext[Job]?.invokeOnCompletion {
                try { server.close() } catch (_: Exception) { }
            }

            val deadline = System.currentTimeMillis() + ACCEPT_BUDGET_MS
            try {
                var accepts = 0
                while (accepts < MAX_ACCEPTS && isRunning && System.currentTimeMillis() < deadline) {
                    accepts++
                    val clientSocket: Socket = try {
                        runInterruptible { server.accept() }
                    } catch (e: CancellationException) {
                        Log.d(TAG, "OAuth loopback wait cancelled")
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "OAuth server accept timed out or was closed", e)
                        break
                    }
                    if (handleClient(clientSocket, onCodeReceived)) return@withContext true
                }
                Log.w(TAG, "OAuth redirect not received within the accept budget")
                false
            } finally {
                closeOnCancellation?.dispose()
                stop()
            }
        }

    /**
     * bind() + awaitCode() for callers that do not need the bound port before
     * opening the browser. Returns the bound port, or null when nothing could be
     * bound or no code arrived within the budget.
     */
    suspend fun start(
        onCodeReceived: (code: String, state: String?) -> Unit
    ): Int? {
        val port = bind() ?: return null
        return if (awaitCode(onCodeReceived)) port else null
    }

    /** Serves one request. Returns true when a code was found and [onCodeReceived] fired. */
    private fun handleClient(socket: Socket, onCodeReceived: (code: String, state: String?) -> Unit): Boolean {
        var gotCode = false
        try {
            socket.use { s ->
                // A silent preconnect would otherwise block this read forever.
                try { s.soTimeout = READ_TIMEOUT_MS } catch (_: Exception) { }
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val firstLine = reader.readLine() ?: return false // e.g. "GET /?code=abc&state=xyz HTTP/1.1"
                Log.d(TAG, "Received HTTP request: $firstLine")

                val pathWithQuery = firstLine.split(" ").getOrNull(1) ?: ""
                val query = if (pathWithQuery.contains("?")) pathWithQuery.substringAfter("?") else ""
                val params = query.split("&").mapNotNull {
                    val parts = it.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()

                val code = params["code"]
                val state = params["state"]

                val htmlResponse = if (!code.isNullOrBlank()) {
                    """
                    <!DOCTYPE html>
                    <html>
                    <head>
                        <meta name="viewport" content="width=device-width, initial-scale=1.0">
                        <title>FocusLock Connected</title>
                        <style>
                            body { font-family: -apple-system, Roboto, sans-serif; background: #121212; color: #E0E0E0; display: flex; flex-direction: column; align-items: center; justify-content: center; height: 90vh; margin: 0; text-align: center; padding: 20px; }
                            .card { background: #1E1E1E; padding: 32px 24px; border-radius: 24px; max-width: 360px; box-shadow: 0 4px 20px rgba(0,0,0,0.5); }
                            h1 { color: #80CBC4; font-size: 22px; margin-bottom: 12px; }
                            p { color: #9E9E9E; font-size: 15px; line-height: 1.5; margin-bottom: 24px; }
                            .btn { display: inline-block; background: #80CBC4; color: #003731; font-weight: bold; padding: 12px 24px; border-radius: 50px; text-decoration: none; font-size: 14px; }
                        </style>
                    </head>
                    <body>
                        <div class="card">
                            <h1>✓ TickTick Connected!</h1>
                            <p>FocusLock has successfully linked your account. You can close this window and return to the app.</p>
                            <a class="btn" href="focuslock://oauth/callback?code=$code&state=${state ?: ""}">Return to FocusLock</a>
                        </div>
                    </body>
                    </html>
                    """.trimIndent()
                } else {
                    // Preconnects / favicon / stray requests: serve a minimal page and
                    // keep waiting — the real ?code= request arrives on another socket.
                    """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Authorization Incomplete</title><style>body{background:#121212;color:#fff;font-family:sans-serif;padding:30px;text-align:center;}</style></head>
                    <body><h2>Authorization Incomplete</h2><p>No authorization code was found in the response.</p></body>
                    </html>
                    """.trimIndent()
                }

                val responseBytes = htmlResponse.toByteArray(Charsets.UTF_8)
                val out: OutputStream = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\n".toByteArray(Charsets.UTF_8))
                out.write("Content-Type: text/html; charset=UTF-8\r\n".toByteArray(Charsets.UTF_8))
                out.write("Content-Length: ${responseBytes.size}\r\n".toByteArray(Charsets.UTF_8))
                out.write("Connection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                out.write(responseBytes)
                out.flush()

                if (!code.isNullOrBlank()) {
                    gotCode = true
                    onCodeReceived(code, state)
                }
            }
        } catch (e: Exception) {
            // Timeouts on silent preconnects and broken sockets are expected here;
            // the accept loop continues with the next connection.
            Log.w(TAG, "Error handling client socket", e)
        }
        return gotCode
    }

    fun stop() {
        isRunning = false
        val socket = serverSocket
        serverSocket = null
        try {
            socket?.close()
        } catch (_: Exception) { }
    }
}
