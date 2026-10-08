package com.jarves.mh.runtime

import com.jarves.mh.model.AgentKind
import com.jarves.mh.model.ProviderKind
import com.jarves.mh.model.ProviderProfile
import com.jarves.mh.model.providersForAgent
import java.io.StringReader
import java.net.Socket
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatGptResponsesGatewayTest {
    @Test
    fun providerRequiresAccountModelAndOnlyUsesClaudeAgent() {
        val profile = ProviderProfile(ProviderKind.CHATGPT, baseUrl = "https://untrusted.example")
        assertEquals("https://api.openai.com/v1", profile.resolvedBaseUrl)
        assertEquals("", profile.model)
        assertTrue(ProviderKind.CHATGPT in providersForAgent(AgentKind.CLAUDE_CODE))
        assertFalse(ProviderKind.CHATGPT in providersForAgent(AgentKind.DEEPSEEK_HARNESS))
    }

    @Test
    fun requestUsesOnlySupportedResponsesFieldsAndForcesOnlyTheChosenNamespacedFunction() {
        val request = ChatGptResponsesAdapter("account-model").request(JSONObject("""{
            "model":"claude-sonnet-4-6", "max_tokens":16000, "temperature":0.8,
            "metadata":{"user_id":"claude-internal"}, "system":[{"type":"text","text":"Build carefully"}],
            "messages":[{"role":"user","content":"Inspect the project"}],
            "tools":[
                {"name":"read_file","description":"Read a file","input_schema":{"type":"object","properties":{"path":{"type":"string"}}}},
                {"name":"write_file","description":"Write a file","input_schema":{"type":"object"}}
            ],
            "tool_choice":{"type":"tool","name":"read_file"}
        }"""))

        assertEquals("account-model", request.getString("model"))
        assertEquals("Build carefully", request.getString("instructions"))
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertEquals("reasoning.encrypted_content", request.getJSONArray("include").getString(0))
        assertEquals(setOf("model", "input", "instructions", "store", "stream", "include", "tools", "tool_choice"), request.keys().asSequence().toSet())
        val namespace = request.getJSONArray("tools").getJSONObject(0)
        assertEquals(1, request.getJSONArray("tools").length())
        assertEquals("namespace", namespace.getString("type"))
        assertEquals("mobile_harness", namespace.getString("name"))
        assertTrue(namespace.getString("description").isNotBlank())
        assertEquals(1, namespace.getJSONArray("tools").length())
        val tool = namespace.getJSONArray("tools").getJSONObject(0)
        assertEquals("read_file", tool.getString("name"))
        assertFalse(tool.getBoolean("strict"))
        assertFalse(tool.has("function"))
        assertEquals("required", request.getString("tool_choice"))
    }

    @Test
    fun automaticToolChoiceMakesAllFunctionsImmediatelyAvailableInTheNamespace() {
        val request = ChatGptResponsesAdapter("model").request(JSONObject("""{
            "messages":[{"role":"user","content":"Inspect"}],
            "tools":[{"name":"read_file","input_schema":{"type":"object"}},{"name":"list_files","input_schema":{"type":"object"}}]
        }"""))
        val tools = request.getJSONArray("tools").getJSONObject(0).getJSONArray("tools")
        assertEquals(2, tools.length())
        assertEquals("read_file", tools.getJSONObject(0).getString("name"))
        assertEquals("list_files", tools.getJSONObject(1).getString("name"))
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertFalse(tools.getJSONObject(0).has("defer_loading"))
        assertEquals("auto", request.getString("tool_choice"))
    }

    @Test
    fun completeToolRoundTripRetainsCallIdsAndEncryptedReasoningForParallelTools() {
        val adapter = ChatGptResponsesAdapter("account-model")
        val reply = adapter.message(JSONObject("""{
            "id":"resp_1","status":"completed","usage":{"input_tokens":12,"output_tokens":20},
            "output":[
                {"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"opaque-reasoning"},
                {"type":"function_call","id":"fc_1","call_id":"call_read","namespace":"mobile_harness","name":"read_file","arguments":"{\"path\":\"app.kt\"}"},
                {"type":"function_call","id":"fc_2","call_id":"call_list","namespace":"mobile_harness","name":"list_files","arguments":"{}"}
            ]
        }"""), "claude-sonnet-4-6")
        assertEquals("tool_use", reply.getString("stop_reason"))
        assertEquals("call_read", reply.getJSONArray("content").getJSONObject(0).getString("id"))
        assertEquals("read_file", reply.getJSONArray("content").getJSONObject(0).getString("name"))
        assertFalse(reply.getJSONArray("content").getJSONObject(0).has("namespace"))
        assertEquals("app.kt", reply.getJSONArray("content").getJSONObject(0).getJSONObject("input").getString("path"))
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "Inspect"))
            .put(JSONObject().put("role", "assistant").put("content", reply.getJSONArray("content")))
            .put(JSONObject("""{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"call_read","content":[{"type":"text","text":"file text"}]},
                {"type":"tool_result","tool_use_id":"call_list","content":"permission denied","is_error":true}
            ]}"""))
        val next = adapter.request(JSONObject().put("messages", messages)).getJSONArray("input")
        assertEquals(6, next.length())
        assertEquals("rs_1", next.getJSONObject(1).getString("id"))
        assertEquals("opaque-reasoning", next.getJSONObject(1).getString("encrypted_content"))
        assertEquals("call_read", next.getJSONObject(2).getString("call_id"))
        assertEquals("mobile_harness", next.getJSONObject(2).getString("namespace"))
        assertEquals("read_file", next.getJSONObject(2).getString("name"))
        assertEquals("call_list", next.getJSONObject(3).getString("call_id"))
        assertEquals("mobile_harness", next.getJSONObject(3).getString("namespace"))
        assertEquals("function_call_output", next.getJSONObject(4).getString("type"))
        assertEquals("file text", next.getJSONObject(4).getString("output"))
        assertEquals("Tool error: permission denied", next.getJSONObject(5).getString("output"))
    }

    @Test
    fun sequentialToolRoundsKeepEachReasoningItemInItsOriginalPosition() {
        val adapter = ChatGptResponsesAdapter("account-model")
        fun toolReply(id: String, reasoningId: String) = adapter.message(JSONObject().put("status", "completed")
            .put("output", JSONArray()
                .put(JSONObject().put("type", "reasoning").put("id", reasoningId).put("summary", JSONArray()).put("encrypted_content", "encrypted-$reasoningId"))
                .put(JSONObject().put("type", "function_call").put("call_id", id).put("namespace", "mobile_harness").put("name", "read_file").put("arguments", "{}"))), "model")
        val first = toolReply("call_one", "rs_one")
        val second = toolReply("call_two", "rs_two")
        val history = JSONArray().put(JSONObject().put("role", "user").put("content", "Inspect"))
        listOf(first to "call_one", second to "call_two").forEach { (reply, id) ->
            history.put(JSONObject().put("role", "assistant").put("content", reply.getJSONArray("content")))
            history.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject()
                .put("type", "tool_result").put("tool_use_id", id).put("content", "Done"))))
        }
        val input = adapter.request(JSONObject().put("messages", history)).getJSONArray("input")
        assertEquals(7, input.length())
        assertEquals("rs_one", input.getJSONObject(1).getString("id"))
        assertEquals("call_one", input.getJSONObject(2).getString("call_id"))
        assertEquals("call_one", input.getJSONObject(3).getString("call_id"))
        assertEquals("rs_two", input.getJSONObject(4).getString("id"))
        assertEquals("call_two", input.getJSONObject(5).getString("call_id"))
        assertEquals("call_two", input.getJSONObject(6).getString("call_id"))
    }

    @Test
    fun mixedTextAndImageToolOutputIsRejectedRatherThanSilentlyLosingTheImage() {
        expectFailure("text tool results only") {
            ChatGptResponsesAdapter("model").request(JSONObject("""{"messages":[{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"call_1","content":[
                    {"type":"text","text":"Screenshot follows"},
                    {"type":"image","source":{"type":"base64","media_type":"image/png","data":"image"}}
                ]}
            ]}]}"""))
        }
    }

    @Test
    fun invalidToolArgumentsFailWithoutExecutingAnEmptyTool() {
        expectFailure("invalid tool arguments") {
            ChatGptResponsesAdapter("model").message(JSONObject("""{
                "status":"completed","output":[{"type":"function_call","call_id":"call_1","namespace":"mobile_harness","name":"delete_file","arguments":"broken-json"}]
            }"""), "model")
        }
    }

    @Test
    fun unexpectedToolNamespaceFailsBeforePassingTheCallToClaude() {
        expectFailure("unexpected namespace") {
            ChatGptResponsesAdapter("model").message(JSONObject("""{
                "status":"completed","output":[{"type":"function_call","call_id":"call_1","namespace":"other_application","name":"read_file","arguments":"{}"}]
            }"""), "model")
        }
    }

    @Test
    fun completedRefusalBecomesReadableAssistantText() {
        val message = ChatGptResponsesAdapter("model").message(JSONObject("""{
            "id":"resp_refused","status":"completed","output":[{"type":"message","content":[{"type":"refusal","refusal":"I cannot help with that."}]}]
        }"""), "model")
        assertEquals("end_turn", message.getString("stop_reason"))
        assertEquals("I cannot help with that.", message.getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun sseRequiresCompletedAndUsesFullFinalOutputRatherThanPartialDeltas() {
        val response = readSse("""
            : keepalive

            event: response.output_text.delta
            data: {"type":"response.output_text.delta","delta":"partial"}

            event: response.completed
            data: {"type":"response.completed","response":{"status":"completed","id":"resp_ok","output":[{"type":"message","content":[{"type":"output_text","text":"Final text"}]}]}}

        """.trimIndent())
        assertEquals("resp_ok", response.getString("id"))
        assertEquals("Final text", response.getJSONArray("output").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text"))
    }

    @Test
    fun sseQuotaErrorInsideHttpSuccessIsFailure() {
        try {
            readSse("data: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"insufficient_quota\",\"message\":\"Plan limit reached\"}}}\n\n")
            fail("A failed SSE response must fail validation")
        } catch (error: ChatGptResponsesException) {
            assertEquals(429, error.status)
            assertTrue(error.message.orEmpty().contains("Plan limit reached"))
        }
    }

    @Test
    fun subscriptionSharingUnavailableHasAnActionableError() {
        try {
            readSse("data: {\"type\":\"error\",\"code\":\"subscription_sharing_usage_unavailable\",\"message\":\"Unavailable\"}\n\n")
            fail("Subscription-sharing rejection must fail")
        } catch (error: ChatGptResponsesException) {
            assertEquals(403, error.status)
            assertTrue(error.message.orEmpty().contains("shared usage settings in ChatGPT"))
        }
    }

    @Test
    fun incompleteAndTruncatedSseNeverBecomeSuccessfulEmptyMessages() {
        expectFailure("max_output_tokens") {
            readSse("data: {\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n\n")
        }
        expectFailure("before completing") {
            readSse("data: {\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}\n\n")
        }
        expectFailure("without a completed response") { readSse("data: [DONE]\n\n") }
    }

    @Test
    fun unauthorizedLoopbackClientNeverObtainsAnOAuthToken() {
        var requestedToken = false
        ChatGptResponsesGateway(ProviderProfile(ProviderKind.CHATGPT, model = "model")) {
            requestedToken = true
            "must-stay-native"
        }.start().use { gateway ->
            val uri = URI(gateway.url)
            Socket("127.0.0.1", uri.port).use { socket ->
                socket.soTimeout = 3_000
                socket.getOutputStream().write("POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:${uri.port}\r\nContent-Length: 2\r\n\r\n{}".toByteArray())
                val result = socket.getInputStream().bufferedReader().readText()
                assertTrue(result.startsWith("HTTP/1.1 401"))
                assertFalse(result.contains(gateway.authorizationToken))
            }
        }
        assertFalse(requestedToken)
    }

    @Test
    fun cliReceivesOnlyTheLoopbackCredential() {
        val config = RuntimeLaunchConfigBuilder.build(
            ProviderProfile(ProviderKind.CHATGPT, model = "account-model"),
            authToken = "loopback-only",
            localGatewayUrl = "http://127.0.0.1:12345",
        )
        assertEquals("loopback-only", config.environment["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("http://127.0.0.1:12345", config.environment["ANTHROPIC_BASE_URL"])
        assertFalse(config.environment.values.contains("https://api.openai.com/v1"))
    }

    private fun readSse(value: String): JSONObject = ChatGptResponsesSse.readCompleted(StringReader(value).buffered())

    private fun expectFailure(message: String, block: () -> Unit) {
        try {
            block()
            fail("Expected failure containing $message")
        } catch (error: Exception) {
            assertTrue("Unexpected error: ${error.message}", error.message.orEmpty().contains(message))
        }
    }
}
