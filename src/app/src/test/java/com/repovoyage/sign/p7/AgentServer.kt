package com.repovoyage.sign.p7

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/** 测试用假云端服务：按序回放脚本响应（跨连接共享队列）并捕获请求 */
class AgentServer : AutoCloseable {

    class CapturedRequest(val path: String, val headers: Map<String, String>, val body: String)

    class ScriptedResponse(val status: Int, val jsonBody: String)

    val requests = mutableListOf<CapturedRequest>()

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port: Int = server.localPort
    private val pending = ConcurrentLinkedQueue<ScriptedResponse>()
    private var thread: Thread? = null

    @Volatile private var closed = false

    fun start(script: List<ScriptedResponse>) {
        pending.addAll(script)
        thread = thread(name = "agent-server", isDaemon = true) {
            while (!closed) {
                val client = try {
                    server.accept()
                } catch (_: Exception) {
                    break
                }
                serve(client)
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { c ->
            c.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(length).let { reader.read(it); String(it) }
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            synchronized(requests) { requests += CapturedRequest(path, headers, body) }
            val response = pending.poll() ?: ScriptedResponse(500, "{}")
            val bytes = response.jsonBody.toByteArray(Charsets.UTF_8)
            c.getOutputStream().apply {
                write(
                    ("HTTP/1.1 ${response.status} x\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8),
                )
                write(bytes)
                flush()
            }
        }
    }

    override fun close() {
        closed = true
        server.close()
    }
}
