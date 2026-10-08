package com.jarves.mh.runtime

import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.network.ConnectionValidation
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Keeps ChatGPT credentials in Android; Claude Code sees only a per-run loopback credential. */
internal class ChatGptResponsesGateway(
    profile: ProviderProfile,
    private val accessToken: suspend () -> String,
) : AutoCloseable {
    private val adapter = ChatGptResponsesAdapter(profile.model)
    private val running = AtomicBoolean(true)
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val transport = ChatGptResponsesTransport()
    val url = "http://127.0.0.1:${server.localPort}"
    val authorizationToken: String = UUID.randomUUID().toString() + UUID.randomUUID().toString()
    @Volatile var failureMessage: String? = null
        private set

    fun start(): ChatGptResponsesGateway = apply {
        Thread({
            while (running.get()) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                if (!running.get() || sockets.size >= MAX_CONNECTIONS) {
                    socket.close()
                    continue
                }
                sockets.add(socket)
                Thread({
                    try {
                        socket.use(::handle)
                    } finally {
                        sockets.remove(socket)
                    }
                }, "mh-chatgpt-request").apply { isDaemon = true; start() }
            }
        }, "mh-chatgpt-gateway").apply { isDaemon = true; start() }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 30_000
        val output = BufferedOutputStream(socket.getOutputStream())
        var authorizedRequest = false
        try {
            require(socket.inetAddress.isLoopbackAddress) { "Only loopback clients are permitted" }
            val input = BufferedInputStream(socket.getInputStream())
            val request = readHttpLine(input) ?: return
            val requestParts = request.split(' ')
            require(requestParts.size == 3 && requestParts[0] == "POST") { "Only POST requests are supported" }
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                val line = readHttpLine(input) ?: error("Incomplete HTTP headers")
                if (line.isEmpty()) break
                require(++headerCount <= 64) { "Too many HTTP headers" }
                val split = line.indexOf(':')
                require(split > 0) { "Invalid HTTP header" }
                val name = line.substring(0, split).lowercase()
                require(!headers.containsKey(name)) { "Duplicate HTTP header" }
                headers[name] = line.substring(split + 1).trim()
            }
            val expectedHost = "127.0.0.1:${server.localPort}"
            if (headers["host"] != expectedHost || !authorized(headers)) {
                writeJson(output, 401, anthropicError("authentication_error", "Invalid local gateway credential"))
                return
            }
            authorizedRequest = true
            require(headers["transfer-encoding"] == null) { "Chunked requests are unsupported" }
            val length = headers["content-length"]?.toIntOrNull()
            require(length != null && length in 1..MAX_BODY_BYTES) { "Invalid or oversized request body" }
            val body = ByteArray(length)
            var offset = 0
            while (offset < body.size) {
                val read = input.read(body, offset, body.size - offset)
                require(read > 0) { "Incomplete request body" }
                offset += read
            }
            val path = requestParts[1].substringBefore('?')
            if (path.endsWith("/count_tokens")) {
                writeJson(output, 200, JSONObject().put("input_tokens", body.size / 4 + 1).toString())
                return
            }
            if (!path.endsWith("/messages")) {
                writeJson(output, 404, anthropicError("not_found_error", "Unsupported gateway endpoint"))
                return
            }
            val source = JSONObject(body.decodeToString())
            val upstreamRequest = adapter.request(source)
            failureMessage = null
            val completed = runBlocking {
                val token = accessToken()
                require(token.isNotBlank()) { "Sign in with ChatGPT before starting a task" }
                transport.execute(upstreamRequest, token)
            }
            val message = adapter.message(completed, source.optString("model"))
            if (source.optBoolean("stream", false)) writeAnthropicStream(output, message)
            else writeJson(output, 200, message.toString())
        } catch (error: Exception) {
            if (!running.get()) return
            if (authorizedRequest) failureMessage = error.message ?: "ChatGPT request failed"
            val status = (error as? ChatGptResponsesException)?.status ?: 502
            val type = when (status) {
                401, 403 -> "authentication_error"
                429 -> "rate_limit_error"
                else -> "api_error"
            }
            runCatching { writeJson(output, status, anthropicError(type, error.message ?: "ChatGPT request failed")) }
        }
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val provided = headers["authorization"]?.removePrefix("Bearer ") ?: headers["x-api-key"] ?: return false
        return MessageDigest.isEqual(provided.toByteArray(), authorizationToken.toByteArray())
    }

    override fun close() {
        running.set(false)
        runCatching { server.close() }
        transport.close()
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    companion object {
        private const val MAX_CONNECTIONS = 4
        private const val MAX_BODY_BYTES = 16 * 1024 * 1024

        /** A successful HTTP status alone is insufficient: Responses can fail inside an SSE event. */
        suspend fun validateConnection(model: String, accessToken: suspend () -> String): ConnectionValidation = withContext(Dispatchers.IO) {
            val transport = ChatGptResponsesTransport()
            try {
                val adapter = ChatGptResponsesAdapter(model)
                val request = adapter.request(JSONObject().put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "Reply with OK."),
                )))
                val token = accessToken()
                require(token.isNotBlank()) { "Sign in with ChatGPT first" }
                adapter.message(transport.execute(request, token), model)
                ConnectionValidation.Success("ChatGPT responded successfully.")
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                ConnectionValidation.Failure(error.message ?: "ChatGPT connection failed", label = "ChatGPT")
            } finally {
                transport.close()
            }
        }

        private fun readHttpLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (line.isEmpty()) null else line.toString().trimEnd('\r')
                if (byte == '\n'.code) return line.toString().trimEnd('\r')
                require(line.length < 8_192) { "HTTP header is too large" }
                line.append(byte.toChar())
            }
        }

        private fun anthropicError(type: String, message: String): String = JSONObject()
            .put("type", "error").put("error", JSONObject().put("type", type).put("message", message)).toString()

        private fun writeJson(output: BufferedOutputStream, code: Int, body: String) {
            val bytes = body.toByteArray()
            output.write("HTTP/1.1 $code ${if (code in 200..299) "OK" else "Error"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
        }

        private fun writeAnthropicStream(output: BufferedOutputStream, message: JSONObject) {
            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n".toByteArray())
            fun event(type: String, value: JSONObject) {
                output.write("event: $type\ndata: $value\n\n".toByteArray())
            }
            val usage = message.getJSONObject("usage")
            event("message_start", JSONObject().put("type", "message_start").put("message", JSONObject(message.toString())
                .put("content", JSONArray()).put("stop_reason", JSONObject.NULL)
                .put("usage", JSONObject().put("input_tokens", usage.optInt("input_tokens")).put("output_tokens", 0))))
            val content = message.getJSONArray("content")
            for (index in 0 until content.length()) {
                val block = content.getJSONObject(index)
                val text = block.optString("type") == "text"
                val start = if (text) JSONObject().put("type", "text").put("text", "")
                else JSONObject().put("type", "tool_use").put("id", block.getString("id")).put("name", block.getString("name")).put("input", JSONObject())
                event("content_block_start", JSONObject().put("type", "content_block_start").put("index", index).put("content_block", start))
                val delta = if (text) JSONObject().put("type", "text_delta").put("text", block.getString("text"))
                else JSONObject().put("type", "input_json_delta").put("partial_json", block.getJSONObject("input").toString())
                event("content_block_delta", JSONObject().put("type", "content_block_delta").put("index", index).put("delta", delta))
                event("content_block_stop", JSONObject().put("type", "content_block_stop").put("index", index))
            }
            event("message_delta", JSONObject().put("type", "message_delta")
                .put("delta", JSONObject().put("stop_reason", message.getString("stop_reason")).put("stop_sequence", JSONObject.NULL))
                .put("usage", JSONObject().put("output_tokens", usage.optInt("output_tokens"))))
            event("message_stop", JSONObject().put("type", "message_stop"))
            output.flush()
        }
    }
}

/** Stateless Responses requests carry the complete Anthropic history and encrypted reasoning. */
internal class ChatGptResponsesAdapter(private val model: String) {
    private val reasoningByCall = ConcurrentHashMap<String, List<JSONObject>>()

    init {
        require(model.isNotBlank()) { "Select a model available to your ChatGPT account first" }
    }

    fun request(source: JSONObject): JSONObject {
        val input = JSONArray()
        val includedReasoning = mutableSetOf<String>()
        val messages = source.optJSONArray("messages") ?: JSONArray()
        for (index in 0 until messages.length()) {
            val message = messages.getJSONObject(index)
            val role = message.optString("role")
            require(role == "user" || role == "assistant") { "Unsupported conversation role" }
            val content = message.opt("content")
            if (content is String) {
                input.put(textMessage(role, content))
                continue
            }
            require(content is JSONArray) { "Unsupported conversation content" }
            for (partIndex in 0 until content.length()) {
                val part = content.getJSONObject(partIndex)
                when (part.optString("type")) {
                    "text" -> input.put(textMessage(role, part.optString("text")))
                    "image" -> {
                        require(role == "user") { "Only user images are supported" }
                        val image = part.getJSONObject("source")
                        val url = when (image.getString("type")) {
                            "base64" -> "data:${image.getString("media_type")};base64,${image.getString("data")}"
                            "url" -> image.getString("url")
                            else -> error("Unsupported image source")
                        }
                        input.put(JSONObject().put("role", role).put("content", JSONArray().put(
                            JSONObject().put("type", "input_image").put("image_url", url),
                        )))
                    }
                    "tool_use" -> {
                        val id = part.getString("id")
                        reasoningByCall[id].orEmpty().forEach { reasoning ->
                            if (includedReasoning.add(reasoning.getString("id"))) input.put(JSONObject(reasoning.toString()))
                        }
                        input.put(JSONObject().put("type", "function_call").put("call_id", id).put("namespace", TOOL_NAMESPACE)
                            .put("name", part.getString("name")).put("arguments", part.getJSONObject("input").toString()))
                    }
                    "tool_result" -> input.put(JSONObject().put("type", "function_call_output")
                        .put("call_id", part.getString("tool_use_id"))
                        .put("output", (if (part.optBoolean("is_error")) "Tool error: " else "") + toolOutput(part.opt("content"))))
                    "thinking", "redacted_thinking" -> Unit // Native reasoning is retained separately, never converted to user text.
                    else -> error("ChatGPT does not support this attachment type: ${part.optString("type")}")
                }
            }
        }
        val target = JSONObject().put("model", model).put("input", input).put("store", false).put("stream", true)
            .put("include", JSONArray().put("reasoning.encrypted_content"))
        val instructions = valueText(source.opt("system"))
        if (instructions.isNotBlank()) target.put("instructions", instructions)
        source.optJSONArray("tools")?.takeIf { it.length() > 0 }?.let { tools ->
            val choice = source.optJSONObject("tool_choice")
            val forcedName = if (choice?.optString("type") == "tool") choice.getString("name") else null
            val functions = JSONArray()
            val names = mutableSetOf<String>()
            for (index in 0 until tools.length()) {
                val tool = tools.getJSONObject(index)
                val name = tool.optString("name")
                require(name.isNotBlank()) { "Unsupported unnamed tool" }
                require(names.add(name)) { "Duplicate tool name: $name" }
                if (forcedName != null && name != forcedName) continue
                functions.put(JSONObject().put("type", "function").put("name", name)
                    .put("description", tool.optString("description"))
                    .put("parameters", tool.optJSONObject("input_schema") ?: JSONObject().put("type", "object"))
                    .put("strict", false))
            }
            require(functions.length() > 0) { "The requested tool is not available" }
            // ChatGPT plan usage accepts function tools only inside namespaces (or additional_tools input items).
            target.put("tools", JSONArray().put(JSONObject().put("type", "namespace").put("name", TOOL_NAMESPACE)
                .put("description", "Tools provided by Mobile Harness for working on the user's project.")
                .put("tools", functions)))
            target.put("tool_choice", when (choice?.optString("type")) {
                // A single permitted function preserves Anthropic's forced-tool choice without a namespace-ambiguous name.
                "any", "tool" -> "required"
                "none" -> "none"
                else -> "auto"
            })
        }
        return target
    }

    fun message(response: JSONObject, downstreamModel: String): JSONObject {
        require(response.optString("status") == "completed") { "ChatGPT response did not complete" }
        val content = JSONArray()
        val output = response.optJSONArray("output") ?: JSONArray()
        val reasoning = mutableListOf<JSONObject>()
        var toolCalls = false
        for (index in 0 until output.length()) {
            val item = output.getJSONObject(index)
            when (item.optString("type")) {
                "reasoning" -> {
                    require(item.optString("id").isNotBlank()) { "ChatGPT returned reasoning without an ID" }
                    // Only encrypted items can be replayed with store=false. Preserve the complete item.
                    if (!item.isNull("encrypted_content") && item.optString("encrypted_content").isNotBlank()) {
                        reasoning += JSONObject(item.toString())
                    }
                }
                "message" -> {
                    val parts = item.optJSONArray("content") ?: JSONArray()
                    for (partIndex in 0 until parts.length()) {
                        val part = parts.getJSONObject(partIndex)
                        val text = when (part.optString("type")) {
                            "output_text" -> part.optString("text")
                            "refusal" -> part.optString("refusal")
                            else -> ""
                        }
                        if (text.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", text))
                    }
                }
                "function_call" -> {
                    val id = item.getString("call_id")
                    require(id.isNotBlank() && item.optString("name").isNotBlank()) { "ChatGPT returned an invalid tool call" }
                    require(item.optString("namespace") == TOOL_NAMESPACE) { "ChatGPT returned a tool from an unexpected namespace; the tool was not run" }
                    val arguments = try {
                        JSONObject(item.getString("arguments"))
                    } catch (_: Exception) {
                        throw ChatGptResponsesException(502, "ChatGPT returned invalid tool arguments; the tool was not run")
                    }
                    if (reasoning.isNotEmpty()) reasoningByCall[id] = reasoning.map { JSONObject(it.toString()) }
                    content.put(JSONObject().put("type", "tool_use").put("id", id).put("name", item.getString("name")).put("input", arguments))
                    toolCalls = true
                }
                else -> throw ChatGptResponsesException(502, "ChatGPT returned an unsupported output item: ${item.optString("type")}")
            }
        }
        require(content.length() > 0) { "ChatGPT completed without text or a tool call" }
        require(reasoningByCall.size <= 256) { "ChatGPT tool history limit reached; start a new task" }
        val usage = response.optJSONObject("usage") ?: JSONObject()
        return JSONObject().put("id", response.optString("id").ifBlank { "msg_${UUID.randomUUID()}" })
            .put("type", "message").put("role", "assistant").put("model", downstreamModel.ifBlank { model })
            .put("content", content).put("stop_reason", if (toolCalls) "tool_use" else "end_turn").put("stop_sequence", JSONObject.NULL)
            .put("usage", JSONObject().put("input_tokens", usage.optInt("input_tokens")).put("output_tokens", usage.optInt("output_tokens")))
    }

    private fun textMessage(role: String, text: String): JSONObject = JSONObject().put("role", role).put("content", JSONArray().put(
        JSONObject().put("type", if (role == "assistant") "output_text" else "input_text").put("text", text),
    ))

    private fun valueText(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val part = value.optJSONObject(index) ?: continue
                if (part.optString("type") == "text") {
                    if (isNotEmpty()) append('\n')
                    append(part.optString("text"))
                }
            }
        }.ifBlank { value.toString() }
        null, JSONObject.NULL -> ""
        else -> value.toString()
    }

    private fun toolOutput(value: Any?): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val block = value.optJSONObject(index)
                require(block != null && block.optString("type") == "text") {
                    "ChatGPT currently supports text tool results only; this tool returned an image or another attachment"
                }
                if (isNotEmpty()) append('\n')
                append(block.optString("text"))
            }
        }
        null, JSONObject.NULL -> ""
        else -> error("ChatGPT received an unsupported tool result")
    }

    private companion object {
        const val TOOL_NAMESPACE = "mobile_harness"
    }
}

internal class ChatGptResponsesException(val status: Int, message: String) : Exception(message)

/** Public Responses transport: fixed origin, no redirects, and a required completed SSE event. */
internal class ChatGptResponsesTransport : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun execute(request: JSONObject, token: String): JSONObject {
        check(!closed.get()) { "ChatGPT task was stopped" }
        val connection = URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection
        connections.add(connection)
        try {
            check(!closed.get()) { "ChatGPT task was stopped" }
            connection.instanceFollowRedirects = false
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 180_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "text/event-stream")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(request.toString().toByteArray()) }
            val status = connection.responseCode
            if (status !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { reader ->
                    val chars = CharArray(8_192)
                    val count = reader.read(chars)
                    if (count > 0) String(chars, 0, count) else ""
                }.orEmpty()
                val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
                val detail = error?.optString("message").orEmpty()
                if (error?.optString("code") == "subscription_sharing_usage_unavailable") {
                    throw ChatGptResponsesException(403, SUBSCRIPTION_USAGE_UNAVAILABLE)
                }
                throw ChatGptResponsesException(status, when (status) {
                    401 -> "ChatGPT sign-in expired or was rejected. Sign in again."
                    403 -> "Your ChatGPT account cannot access this model or request. ${detail.take(500)}".trim()
                    429 -> "ChatGPT usage limit reached. ${detail.take(500)}".trim()
                    else -> "ChatGPT request failed (HTTP $status). ${detail.take(500)}".trim()
                })
            }
            return connection.inputStream.bufferedReader().use(ChatGptResponsesSse::readCompleted)
        } finally {
            connections.remove(connection)
            connection.disconnect()
        }
    }

    override fun close() {
        closed.set(true)
        connections.forEach(HttpURLConnection::disconnect)
        connections.clear()
    }
}

internal object ChatGptResponsesSse {
    private const val MAX_STREAM_CHARACTERS = 32 * 1024 * 1024
    private const val MAX_LINE_CHARACTERS = 4 * 1024 * 1024

    fun readCompleted(reader: BufferedReader): JSONObject {
        var characters = 0
        var eventType = ""
        val data = StringBuilder()
        val deadline = System.nanoTime() + 5 * 60 * 1_000_000_000L
        fun event(): JSONObject? {
            if (data.isEmpty()) return null
            val payload = data.toString()
            data.setLength(0)
            if (payload == "[DONE]") throw ChatGptResponsesException(502, "ChatGPT stream ended without a completed response")
            val json = try { JSONObject(payload) } catch (_: Exception) {
                throw ChatGptResponsesException(502, "ChatGPT returned an invalid response event")
            }
            val type = json.optString("type").ifBlank { eventType }
            val response = json.optJSONObject("response")
            when (type) {
                "response.completed" -> {
                    if (response == null || response.optString("status") != "completed") {
                        throw ChatGptResponsesException(502, "ChatGPT response did not complete")
                    }
                    return response
                }
                "response.failed", "response.incomplete", "error" -> {
                    val error = response?.optJSONObject("error") ?: json.optJSONObject("error") ?: json
                    val detail = error.optString("message").ifBlank {
                        response?.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
                    }.ifBlank { "The response did not complete" }
                    val code = error.optString("code")
                    if (code == "subscription_sharing_usage_unavailable") {
                        throw ChatGptResponsesException(403, SUBSCRIPTION_USAGE_UNAVAILABLE)
                    }
                    val status = when {
                        code.contains("rate_limit") || code.contains("quota") || code.contains("usage_limit") -> 429
                        code.contains("authentication") || code.contains("invalid_api_key") -> 401
                        else -> 502
                    }
                    throw ChatGptResponsesException(status, "ChatGPT request failed: ${detail.take(700)}")
                }
            }
            return null
        }
        while (true) {
            require(System.nanoTime() < deadline) { "ChatGPT response timed out" }
            val line = readBoundedLine(reader) ?: break
            characters += line.length
            require(characters <= MAX_STREAM_CHARACTERS) { "ChatGPT response exceeded the size limit" }
            when {
                line.isEmpty() -> {
                    event()?.let { return it }
                    eventType = ""
                }
                line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        event()?.let { return it }
        throw ChatGptResponsesException(502, "ChatGPT disconnected before completing the response")
    }

    private fun readBoundedLine(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val character = reader.read()
            if (character < 0) return if (line.isEmpty()) null else line.toString().trimEnd('\r')
            if (character == '\n'.code) return line.toString().trimEnd('\r')
            require(line.length < MAX_LINE_CHARACTERS) { "ChatGPT response event exceeded the size limit" }
            line.append(character.toChar())
        }
    }
}

private const val SUBSCRIPTION_USAGE_UNAVAILABLE =
    "ChatGPT subscription sharing is unavailable. Check this app's access and shared usage settings in ChatGPT, then sign in again."
