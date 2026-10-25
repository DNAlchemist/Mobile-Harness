package com.jarves.mh.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Date

class ChatGptSignInTest {
    @Test fun completeFlowBindsListenerBeforeOpeningBrowserAndValidatesIdentity() = runBlocking {
        val time = 1_790_000_000_000L
        val key = RSAKeyGenerator(2048).keyID("test").generate()
        val store = MemoryStore()
        var parameters = emptyMap<String, String>()
        val controller = ChatGptAuthController(store, AuthHttpClient { url, form, _ ->
            when (url) {
                ChatGptOAuth.TOKEN -> {
                    assertEquals("issued-client", form?.get("client_id"))
                    assertEquals(parameters["redirect_uri"], form?.get("redirect_uri"))
                    assertEquals(ChatGptOAuth.RESOURCE, form?.get("resource"))
                    assertEquals(parameters["code_challenge"], ChatGptOAuth.challenge(form!!["code_verifier"]!!))
                    val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(), JWTClaimsSet.Builder()
                        .issuer(ChatGptOAuth.ISSUER).audience("issued-client").subject("subject")
                        .claim("nonce", parameters["nonce"]).claim("email", "test@example.invalid")
                        .issueTime(Date(time)).expirationTime(Date(time + 60_000)).build())
                        .apply { sign(RSASSASigner(key)) }.serialize()
                    AuthHttpResponse(200, JSONObject().put("token_type", "Bearer").put("access_token", "test-access")
                        .put("refresh_token", "test-refresh").put("id_token", jwt).put("expires_in", 3600)
                        .put("scope", "openid offline_access chatgpt.tokens.use.direct").toString())
                }
                "${ChatGptOAuth.ISSUER}/.well-known/jwks.json" -> AuthHttpResponse(200, JWKSet(key.toPublicJWK()).toString())
                "${ChatGptOAuth.RESOURCE}/models" -> AuthHttpResponse(200, """{"models":[{"slug":"test-model","display_name":"Test Model","visibility":"list"}]}""")
                else -> error("Unexpected request")
            }
        }, { time }, Dispatchers.Unconfined)
        controller.signIn { url ->
            parameters = query(url)
            val redirect = URI(parameters["redirect_uri"]!!)
            assertEquals("127.0.0.1", redirect.host)
            assertEquals("/auth/callback", redirect.path)
            // A connection succeeds right inside openBrowser, before it returns.
            callback(redirect, "state=${parameters["state"]}&code=test-code&client_id=issued-client")
        }
        assertTrue(controller.state.value.connected)
        assertTrue(controller.state.value.planEnabled)
        assertEquals("test@example.invalid", controller.state.value.email)
        assertEquals(listOf("test-model"), controller.state.value.models.map { it.id })
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.error)
        assertEquals("subject", store.value.activeAccount()?.subject)
    }

    @Test fun invalidCodeRetainsIssuedClientForFreshReauthorization() = runBlocking {
        val store = MemoryStore()
        val controller = ChatGptAuthController(store, AuthHttpClient { _, _, _ -> AuthHttpResponse(400, """{"error":"invalid_grant"}""") },
            browserDispatcher = Dispatchers.Unconfined)
        controller.signIn { url ->
            val parameters = query(url)
            callback(URI(parameters["redirect_uri"]!!), "state=${parameters["state"]}&code=expired-code&client_id=issued-client")
        }
        assertEquals("issued-client", store.value.activeClientId)
        assertNull(store.value.activeAccount()?.tokens)
        assertTrue(controller.state.value.error!!.contains("registration was retained"))
        val retry = launch {
            controller.signIn { url ->
                assertEquals("issued-client", query(url)["client_id"])
                assertFalse(query(url).containsKey("agent_name_hint"))
                controller.cancelSignIn()
            }
        }
        retry.join()
        assertTrue(retry.isCancelled)
        assertFalse(controller.state.value.busy)
        assertNull(controller.state.value.error)
        assertNull(store.value.activeAccount()?.tokens)
    }

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate {
        val pair = it.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    private fun callback(redirect: URI, query: String) {
        Socket(redirect.host, redirect.port).use { socket ->
            socket.getOutputStream().write("GET ${redirect.path}?$query HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray(Charsets.US_ASCII))
        }
    }

    private class MemoryStore : ChatGptSessionStore {
        var value = ChatGptSessions()
        override fun read() = value
        override fun write(sessions: ChatGptSessions) { value = sessions }
    }
}
