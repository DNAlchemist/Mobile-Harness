package com.jarves.mh.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLDecoder
import java.util.Date

class ChatGptOAuthTest {
    private val attempt = OAuthAttempt("http://127.0.0.1:54321/auth/callback", ChatGptOAuth.DYNAMIC_CLIENT, "fresh-state", "fresh-nonce", "verifier")

    @Test fun pkceMatchesRfc7636Vector() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", ChatGptOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val first = ChatGptOAuth.randomValue()
        assertTrue(first.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(first, ChatGptOAuth.randomValue())
    }

    @Test fun authorizeUsesPublicRegistrationAndExactLoopbackAndGrant() {
        val url = URI(ChatGptOAuth.authorizationUrl(attempt, "urn:uuid:host", null))
        val parameters = url.rawQuery.split('&').associate { part ->
            val pair = part.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
        }
        assertEquals("https://auth.openai.com/api/accounts/authorize", url.toString().substringBefore('?'))
        assertEquals(attempt.redirectUri, parameters["redirect_uri"])
        assertEquals(ChatGptOAuth.SCOPES, parameters["scope"])
        assertEquals(ChatGptOAuth.RESOURCE, parameters["resource"])
        assertEquals("Mobile Harness Fork", parameters["agent_name_hint"])
        assertEquals("S256", parameters["code_challenge_method"])
        assertFalse(parameters.containsKey("client_secret"))
    }

    @Test fun callbackRequiresMatchingStateAndIssuedClient() {
        val callback = ChatGptOAuth.callback("/auth/callback?state=fresh-state&code=secret-code&client_id=oaiapp_issued", attempt)
        assertEquals("oaiapp_issued", callback.clientId)
        assertEquals("secret-code", callback.code)
        rejected { ChatGptOAuth.callback("/auth/callback?state=wrong&code=code&client_id=oaiapp_issued", attempt) }
        rejected { ChatGptOAuth.callback("/auth/callback?state=fresh-state&code=code", attempt) }
        rejected { ChatGptOAuth.callback("/callback?state=fresh-state&code=code&client_id=oaiapp_issued", attempt) }
        rejected { ChatGptOAuth.callback("http://localhost/auth/callback?state=fresh-state&code=code&client_id=oaiapp_issued", attempt) }
        rejected { ChatGptOAuth.callback("/auth/callback?state=fresh-state&state=fresh-state&code=code&client_id=oaiapp_issued", attempt) }
    }

    @Test fun returningCallbackCannotReplaceRegistrationAndDenialNeverYieldsCode() {
        val returning = OAuthAttempt(attempt.redirectUri, "oaiapp_original", "fresh-state")
        assertEquals("oaiapp_original", ChatGptOAuth.callback("/auth/callback?state=fresh-state&code=code", returning).clientId)
        rejected { ChatGptOAuth.callback("/auth/callback?state=fresh-state&code=code&client_id=oaiapp_other", returning) }
        rejected { ChatGptOAuth.callback("/auth/callback?state=fresh-state&error=access_denied&code=code&client_id=oaiapp_issued", attempt) }
    }

    @Test fun accountCatalogFiltersVisibilityAndPreservesServerOrder() {
        val models = ChatGptOAuth.parseModels("""{"models":[
            {"slug":"z-model","display_name":"Z","visibility":"list"},
            {"slug":"hidden","visibility":"hidden"},
            {"slug":"a-model","display_name":"A","visibility":"list"}
        ]}""")
        assertEquals(listOf("z-model", "a-model"), models.map { it.id })
        assertEquals(listOf("Z", "A"), models.map { it.displayName })
    }

    @Test fun verifiesSignatureAndRejectsWrongIssuerAudienceNonceExpiryAndReturningAccount() {
        val time = 1_790_000_000_000L
        val key = RSAKeyGenerator(2048).keyID("test-key").generate()
        val verifier = ChatGptIdTokenVerifier(AuthHttpClient { _, _, _ -> AuthHttpResponse(200, JWKSet(key.toPublicJWK()).toString()) }, { time })
        fun token(issuer: String = ChatGptOAuth.ISSUER, audience: String = "oaiapp_issued", nonce: String = "fresh-nonce", subject: String = "account-a", expires: Long = time + 60_000): String {
            val claims = JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject).claim("nonce", nonce)
                .claim("email", "test@example.invalid").issueTime(Date(time)).expirationTime(Date(expires)).build()
            return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build(), claims).apply { sign(RSASSASigner(key)) }.serialize()
        }
        assertEquals("account-a", verifier.verify(token(), "oaiapp_issued", "fresh-nonce", "account-a").subject)
        rejected { verifier.verify(token(issuer = "https://attacker.invalid"), "oaiapp_issued", "fresh-nonce", null) }
        rejected { verifier.verify(token(audience = "other-client"), "oaiapp_issued", "fresh-nonce", null) }
        rejected { verifier.verify(token(nonce = "old-nonce"), "oaiapp_issued", "fresh-nonce", null) }
        rejected { verifier.verify(token(expires = time - 10_000), "oaiapp_issued", "fresh-nonce", null) }
        rejected { verifier.verify(token(subject = "account-b"), "oaiapp_issued", "fresh-nonce", "account-a") }
        val forgedKey = RSAKeyGenerator(2048).keyID("test-key").generate()
        val parsed = SignedJWT.parse(token())
        val forged = SignedJWT(parsed.header, parsed.jwtClaimsSet).apply { sign(RSASSASigner(forgedKey)) }.serialize()
        rejected { verifier.verify(forged, "oaiapp_issued", "fresh-nonce", null) }
    }

    @Test fun unknownKeyRefreshesJwks() {
        val time = 1_790_000_000_000L
        val old = RSAKeyGenerator(2048).keyID("old").generate()
        val new = RSAKeyGenerator(2048).keyID("new").generate()
        var requests = 0
        val verifier = ChatGptIdTokenVerifier(AuthHttpClient { _, _, _ ->
            requests++
            AuthHttpResponse(200, JWKSet((if (requests == 1) old else new).toPublicJWK()).toString())
        }, { time })
        val jwt = SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("new").build(), JWTClaimsSet.Builder()
            .issuer(ChatGptOAuth.ISSUER).audience("client").subject("subject").issueTime(Date(time)).expirationTime(Date(time + 60_000)).build())
            .apply { sign(RSASSASigner(new)) }.serialize()
        assertEquals("subject", verifier.verify(jwt, "client", null, null).subject)
        assertEquals(2, requests)
    }

    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected sign-in rejection") } catch (_: ChatGptAuthException) { }
    }
}
