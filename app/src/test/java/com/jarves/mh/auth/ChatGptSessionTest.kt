package com.jarves.mh.auth

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChatGptSessionTest {
    private val time = 1_790_000_000_000L
    private fun session(expiresAt: Long = time + 3600_000, scopes: Set<String> = setOf(ChatGptOAuth.PLAN_SCOPE, "offline_access")): ChatGptSessions = ChatGptSessions(
        "urn:uuid:00000000-0000-0000-0000-000000000001", "issued-client",
        listOf(ChatGptAccount("issued-client", "subject-a", "test@example.invalid", ChatGptTokens("old-access", "old-refresh", "retained-id", scopes, expiresAt))),
    )

    @Test fun encryptedRecordCodecPreservesSeparateRegistrationsAndSignoutMapping() {
        val first = session()
        val added = first.replace(ChatGptAccount("second-client", "subject-b", "test@example.invalid"))
        val restored = ChatGptSessionCodec.decode(ChatGptSessionCodec.encode(added))
        assertEquals(2, restored.accounts.size)
        assertEquals(first.hostId, restored.hostId)
        assertEquals("issued-client", restored.activeClientId)
        assertEquals("old-refresh", restored.activeAccount()?.tokens?.refreshToken)
        val signedOut = restored.replace(restored.activeAccount()!!.withoutTokens())
        val signedOutAgain = ChatGptSessionCodec.decode(ChatGptSessionCodec.encode(signedOut))
        assertNull(signedOutAgain.activeAccount()?.tokens)
        assertEquals("subject-a", signedOutAgain.activeAccount()?.subject)
        assertEquals("issued-client", signedOutAgain.activeAccount()?.clientId)
        assertEquals(first.hostId, signedOutAgain.hostId)
    }

    @Test fun rotatingRefreshIsSerializedAndWrittenAsOneRecord() = runBlocking {
        val store = MemoryStore(session(expiresAt = time - 1))
        var refreshRequests = 0
        val controller = ChatGptAuthController(store, AuthHttpClient { url, form, _ ->
            assertEquals(ChatGptOAuth.TOKEN, url)
            assertEquals("old-refresh", form?.get("refresh_token"))
            assertEquals("issued-client", form?.get("client_id"))
            assertEquals(ChatGptOAuth.RESOURCE, form?.get("resource"))
            assertFalse(form!!.containsKey("scope"))
            refreshRequests++
            AuthHttpResponse(200, """{"token_type":"Bearer","access_token":"new-access","refresh_token":"new-refresh","expires_in":3600,"scope":"offline_access chatgpt.tokens.use.direct"}""")
        }, { time })
        val results = listOf(async { controller.validAccessToken() }, async { controller.validAccessToken() }).map { it.await() }
        assertEquals(listOf("new-access", "new-access"), results)
        assertEquals(1, refreshRequests)
        assertEquals(1, store.writes)
        assertEquals("new-refresh", store.value.activeAccount()?.tokens?.refreshToken)
        assertEquals("retained-id", store.value.activeAccount()?.tokens?.idToken)
    }

    @Test fun missingPlanGrantPreventsNetworkRequests() = runBlocking {
        var requests = 0
        val controller = ChatGptAuthController(MemoryStore(session(scopes = setOf("openid"))), AuthHttpClient { _, _, _ ->
            requests++; AuthHttpResponse(500, "")
        }, { time })
        try { controller.validAccessToken(); fail("Expected plan permission rejection") } catch (_: ChatGptAuthException) { }
        assertEquals(0, requests)
    }

    @Test fun expectedAccountMismatchNeverRefreshesOrReturnsAnotherAccountsToken() = runBlocking {
        var requests = 0
        val store = MemoryStore(session(expiresAt = time - 1))
        val controller = ChatGptAuthController(store, AuthHttpClient { _, _, _ ->
            requests++; AuthHttpResponse(500, "")
        }, { time })
        try {
            controller.validAccessToken(expectedAccountId = "previously-selected-account")
            fail("Expected account mismatch rejection")
        } catch (failure: ChatGptAuthException) {
            assertEquals("The selected ChatGPT account changed. Try the request again.", failure.message)
        }
        assertEquals(0, requests)
        assertEquals(0, store.writes)
        assertEquals("old-access", store.value.activeAccount()?.tokens?.accessToken)
    }

    @Test fun invalidRefreshClearsSecretsButRetainsRegistrationAndHost() = runBlocking {
        val before = session(expiresAt = time - 1)
        val store = MemoryStore(before)
        val controller = ChatGptAuthController(store, AuthHttpClient { _, _, _ ->
            AuthHttpResponse(400, """{"error":"invalid_grant"}""")
        }, { time })
        try { controller.validAccessToken(); fail("Expected expired session rejection") } catch (_: ChatGptAuthException) { }
        assertNull(store.value.activeAccount()?.tokens)
        assertEquals(before.hostId, store.value.hostId)
        assertEquals("issued-client", store.value.activeAccount()?.clientId)
        assertEquals("subject-a", store.value.activeAccount()?.subject)
        assertFalse(controller.state.value.connected)
    }

    @Test fun everyDocumentedUnusableRefreshErrorClearsTokensAndPreservesHostAndAccountMapping() = runBlocking {
        for (code in listOf("invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused")) {
            val before = session(expiresAt = time - 1)
            val store = MemoryStore(before)
            val controller = ChatGptAuthController(store, AuthHttpClient { _, _, _ ->
                AuthHttpResponse(400, """{"error":"$code"}""")
            }, { time })
            try { controller.validAccessToken(); fail("Expected rejection for $code") } catch (_: ChatGptAuthException) { }
            assertNull("$code must clear unusable credentials", store.value.activeAccount()?.tokens)
            assertEquals(before.hostId, store.value.hostId)
            assertEquals("issued-client", store.value.activeClientId)
            assertEquals("subject-a", store.value.activeAccount()?.subject)
            assertEquals("test@example.invalid", store.value.activeAccount()?.email)
            assertFalse(controller.state.value.connected)
            assertTrue(controller.state.value.models.isEmpty())
        }
    }

    @Test fun temporaryRefreshFailureAndInvalidClientKeepCredentialsForRecovery() = runBlocking {
        for ((status, code) in listOf(503 to "temporarily_unavailable", 400 to "invalid_client")) {
            val before = session(expiresAt = time - 1)
            val store = MemoryStore(before)
            val controller = ChatGptAuthController(store, AuthHttpClient { _, _, _ ->
                AuthHttpResponse(status, """{"error":"$code"}""")
            }, { time })
            try { controller.validAccessToken(); fail("Expected rejection for $code") } catch (_: ChatGptAuthException) { }
            assertNotNull(store.value.activeAccount()?.tokens)
            assertEquals("old-refresh", store.value.activeAccount()?.tokens?.refreshToken)
            assertEquals(0, store.writes)
        }
    }

    @Test fun signoutRevokesRenewableSessionAndWipesAllTokens() = runBlocking {
        val before = session()
        val store = MemoryStore(before)
        var revocations = 0
        val controller = ChatGptAuthController(store, AuthHttpClient { url, form, bearer ->
            assertNull(bearer)
            if (url == ChatGptOAuth.DISCOVERY) {
                AuthHttpResponse(200, """{"issuer":"https://auth.openai.com","revocation_endpoint":"https://auth.openai.com/api/accounts/oauth/revoke"}""")
            } else {
                revocations++
                assertEquals("https://auth.openai.com/api/accounts/oauth/revoke", url)
                assertEquals("issued-client", form?.get("client_id"))
                assertEquals("old-refresh", form?.get("token"))
                assertEquals("refresh_token", form?.get("token_type_hint"))
                AuthHttpResponse(200, "")
            }
        }, { time })
        controller.signOut()
        assertEquals(1, revocations)
        assertNull(store.value.activeAccount()?.tokens)
        assertEquals(before.hostId, store.value.hostId)
        assertEquals("issued-client", store.value.activeClientId)
        assertFalse(controller.state.value.connected)
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.error)
    }

    @Test fun failedRevocationStillClearsLocalTokensAndReportsRemoteStatus() = runBlocking {
        val store = MemoryStore(session())
        val controller = ChatGptAuthController(store, AuthHttpClient { _, _, _ ->
            throw java.io.IOException("Do not expose an upstream URL or token")
        }, { time })
        controller.signOut()
        assertNull(store.value.activeAccount()?.tokens)
        assertFalse(controller.state.value.busy)
        assertTrue(controller.state.value.error!!.contains("Remote revocation was not confirmed"))
        assertFalse(controller.state.value.error!!.contains("upstream"))
    }

    private class MemoryStore(var value: ChatGptSessions) : ChatGptSessionStore {
        var writes = 0
        override fun read() = value
        override fun write(sessions: ChatGptSessions) { value = sessions; writes++ }
    }
}
