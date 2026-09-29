package com.earmark.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** Local HTTP server standing in for Anthropic / ElevenLabs / OpenAI in tests. */
class FakeServer(private val handler: (Request) -> Response) : AutoCloseable {
    data class Request(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: String)
    data class Response(val status: Int, val body: ByteArray, val contentType: String = "application/json") {
        constructor(status: Int, body: String, contentType: String = "application/json") : this(status, body.toByteArray(), contentType)
    }

    val requests = CopyOnWriteArrayList<Request>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex ->
            val req = Request(
                ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
                ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            requests += req
            val resp = handler(req)
            ex.responseHeaders.add("Content-Type", resp.contentType)
            ex.sendResponseHeaders(resp.status, if (resp.body.isEmpty()) -1 else resp.body.size.toLong())
            if (resp.body.isNotEmpty()) ex.responseBody.use { it.write(resp.body) } else ex.close()
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
