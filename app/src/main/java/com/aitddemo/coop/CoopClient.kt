package com.aitddemo.coop

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * The WebSocket connection to the co-op server. It runs on OkHttp's own threads and never blocks the game or the screen: [send] only queues a frame, and the
 * listener is called on OkHttp's thread (the [CoopModel] moves what it gets to the main thread).
 */
class CoopClient(private val listener: Listener) {
    interface Listener {
        fun onOpen()
        fun onEvent(event: ServerEvent)
        /** The connection ended (by us, by the server, or by the network): [reason] is for the screen. */
        fun onClosed(reason: String)
    }

    private val http = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS) // a dead connection is noticed
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // the socket stays open; the ping above watches it
        .build()

    /** Only for [warmUp]: a sleeping server (Render's free plan) answers the first request after about a minute. */
    private val waker = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(100, TimeUnit.SECONDS)
        .callTimeout(110, TimeUnit.SECONDS)
        .build()

    /**
     * Wakes a sleeping server: asks for its health page (http or https at the host of the WebSocket address). [done] is called on OkHttp's thread when the server has answered or the
     * attempt failed; false when the address cannot be used. Nothing is sent but the request for the page.
     */
    fun warmUp(url: String, done: (Boolean) -> Unit): Boolean {
        val request = try {
            Request.Builder().url(healthUrl(url) ?: return false).build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        waker.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = done(false)
            override fun onResponse(call: okhttp3.Call, response: Response) { response.use { done(it.isSuccessful) } }
        })
        return true
    }

    @Volatile private var socket: WebSocket? = null
    @Volatile private var closing = false

    /** Opens the connection; [url] is ws://... (local tests) or wss://... (production). Returns false for an address that cannot be a WebSocket URL. */
    fun connect(url: String): Boolean {
        val request = try {
            Request.Builder().url(url.trim()).build()
        } catch (_: IllegalArgumentException) {
            return false
        }
        closing = false
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket === webSocket) listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                CoopJson.parse(text)?.let { listener.onEvent(it) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) finish(if (reason.isNotEmpty()) reason else "the server closed the connection")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) finish(t.message ?: "the connection failed")
            }
        })
        return true
    }

    private fun finish(reason: String) {
        socket = null
        if (!closing) listener.onClosed(reason)
    }

    /** Queues a text frame; false when there is no connection. */
    fun send(text: String): Boolean = socket?.send(text) ?: false

    val isOpen: Boolean get() = socket != null

    fun close() {
        closing = true
        socket?.close(1000, "bye")
        socket = null
    }
}
