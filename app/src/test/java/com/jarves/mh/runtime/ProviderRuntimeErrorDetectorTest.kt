package com.jarves.mh.runtime

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderRuntimeErrorDetectorTest {
    @Test
    fun userNotFoundIsFatal() {
        assertEquals(
            "User not found. Check the API key and provider account.",
            ProviderRuntimeErrorDetector.detect("Failed to authenticate. API Error: 401 User not found."),
        )
    }

    @Test
    fun authenticationRetryIsFatalImmediately() {
        val event = """{"type":"system","subtype":"api_retry","attempt":1,"error_status":401,"error":"authentication_failed"}"""
        assertEquals(
            "The provider rejected the saved API key.",
            ProviderRuntimeErrorDetector.detect(event),
        )
    }

    @Test
    fun ordinaryRuntimeOutputIsNotFatal() {
        assertNull(ProviderRuntimeErrorDetector.detect("Claude Code connected"))
    }

    @Test
    fun sourceTextInToolOutputAndConversationIsNotAProviderError() {
        val events = listOf(
            """{"type":"user","message":{"content":[{"type":"tool_result","content":"return \"user not found\" in value"}]}}""",
            """{"type":"assistant","message":{"content":[{"type":"text","text":"HTTP 401: authentication failed"}]}}""",
            """{"type":"stream_event","event":{"type":"content_block_delta","delta":{"text":"quota expired"}}}""",
            """{"type":"result","is_error":false,"result":"Investigated the rate limit."}""",
            """ordinary output containing user not found""",
        )
        events.forEach { assertNull(it, ProviderRuntimeErrorDetector.detect(it)) }
    }

    @Test
    fun structuredProviderErrorsStillStopTheRun() {
        val events = listOf(
            """{"type":"error","error":{"type":"authentication_error","message":"user not found"}}""",
            """{"type":"stream_event","event":{"type":"error","error":{"type":"authentication_error","message":"user not found"}}}""",
            """{"type":"result","is_error":true,"error_status":401,"result":"user not found"}""",
        )
        events.forEach {
            assertEquals("User not found. Check the API key and provider account.", ProviderRuntimeErrorDetector.detect(it))
        }
    }

    @Test
    fun editFailureQuotingSourceKeepsTheCliReason() {
        val reason = "Edit failed: old string contains 'user not found'; no match"
        val event = JSONObject().put("type", "result").put("is_error", true).put("result", reason)
        val edit = JSONObject("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Edit","input":{"old_string":"user not found"}}]}}""")
        val tool = JSONObject("""{"type":"user","message":{"content":[{"type":"tool_result","is_error":true,"content":"user not found"}]}}""")
        assertNull(ProviderRuntimeErrorDetector.detect(edit.toString()))
        assertNull(ProviderRuntimeErrorDetector.detect(tool.toString()))
        assertNull(ProviderRuntimeErrorDetector.detect(event.toString()))
        assertEquals(reason, ProviderRuntimeErrorDetector.failureDetail(event))
        assertEquals(reason, ProviderRuntimeErrorDetector.failureDetail(IllegalStateException(reason)))
    }

    @Test
    fun errorsArrayAndUntypedErrorAreNotAuthenticationSignals() {
        val result = JSONObject("""{"type":"result","is_error":true,"errors":["Edit failed","user not found in fixture"]}""")
        val error = JSONObject("""{"type":"error","error":{"message":"user not found in fixture"}}""")
        assertNull(ProviderRuntimeErrorDetector.detect(result.toString()))
        assertNull(ProviderRuntimeErrorDetector.detect(error.toString()))
        assertEquals("Edit failed\nuser not found in fixture", ProviderRuntimeErrorDetector.failureDetail(result))
        assertEquals("user not found in fixture", ProviderRuntimeErrorDetector.failureDetail(error))
    }

    @Test
    fun reportsConfirmedChatGptAuthSeparatelyFromLimitsAndOtherHttpErrors() {
        val retry = JSONObject("""{"type":"system","subtype":"api_retry","error_status":401,"message":"user not found"}""")
        assertEquals("Claude Code reported an authentication failure on the ChatGPT connection.",
            ProviderRuntimeErrorDetector.detect(retry.toString(), chatGpt = true))
        assertEquals("The provider reported a rate or usage limit (HTTP 429).",
            ProviderRuntimeErrorDetector.detect(retry.put("error_status", 429).toString(), chatGpt = true))
        assertNull(ProviderRuntimeErrorDetector.detect(retry.put("error_status", 500).toString()))
        assertNull(ProviderRuntimeErrorDetector.detect("API Error: 500 lookup: user not found"))
        assertEquals("The provider denied access (HTTP 403).",
            ProviderRuntimeErrorDetector.detect("Failed to authenticate. API Error: 403 forbidden", chatGpt = true))
    }
}
