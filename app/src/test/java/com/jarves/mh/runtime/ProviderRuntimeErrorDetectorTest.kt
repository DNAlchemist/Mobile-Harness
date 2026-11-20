package com.jarves.mh.runtime

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
            """{"type":"error","error":{"message":"user not found"}}""",
            """{"type":"stream_event","event":{"type":"error","error":{"message":"user not found"}}}""",
            """{"type":"result","is_error":true,"result":"user not found"}""",
        )
        events.forEach {
            assertEquals("User not found. Check the API key and provider account.", ProviderRuntimeErrorDetector.detect(it))
        }
    }
}
