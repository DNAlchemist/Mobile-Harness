package com.jarves.mh.auth

import com.jarves.mh.network.DiscoveredModel
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object ChatGptOAuth {
    const val ISSUER = "https://auth.openai.com"
    const val AUTHORIZE = "$ISSUER/api/accounts/authorize"
    const val TOKEN = "$ISSUER/api/accounts/oauth/token"
    const val DISCOVERY = "$ISSUER/.well-known/openid-configuration"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT = "dynamic_agent_client"
    const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
    const val SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"

    fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun form(values: Map<String, String>): String = values.entries.joinToString("&") {
        "${encode(it.key)}=${encode(it.value)}"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun authorizationUrl(attempt: OAuthAttempt, hostId: String, account: ChatGptAccount?): String {
        val parameters = linkedMapOf(
            "client_id" to attempt.clientId,
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to attempt.redirectUri,
            "scope" to SCOPES,
            "resource" to RESOURCE,
            "state" to attempt.state,
            "nonce" to attempt.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge(attempt.verifier),
        )
        if (attempt.clientId == DYNAMIC_CLIENT) parameters["agent_name_hint"] = "Mobile Harness Fork"
        account?.tokens?.idToken?.let { parameters["id_token_hint"] = it }
        account?.email?.takeIf(String::isNotBlank)?.let { parameters["login_hint"] = it }
        // Never log this URL: a returning account may supply an ID-token hint.
        return "$AUTHORIZE?${form(parameters)}"
    }

    fun callback(target: String, attempt: OAuthAttempt): OAuthCallback {
        val uri = runCatching { URI(target) }.getOrElse { throw ChatGptAuthException("Invalid sign-in callback.") }
        checkAuth(!uri.isAbsolute && uri.rawPath == "/auth/callback" && uri.rawFragment == null, "Invalid sign-in callback path.")
        val parameters = linkedMapOf<String, String>()
        for (part in uri.rawQuery.orEmpty().split('&').filter(String::isNotBlank)) {
            val pair = part.split('=', limit = 2)
            val key = runCatching { URLDecoder.decode(pair[0], "UTF-8") }.getOrElse {
                throw ChatGptAuthException("Invalid sign-in callback.")
            }
            val value = runCatching { URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8") }.getOrElse {
                throw ChatGptAuthException("Invalid sign-in callback.")
            }
            checkAuth(parameters.put(key, value) == null, "Duplicate sign-in callback parameters.")
        }
        checkAuth(constantTimeEquals(parameters["state"].orEmpty(), attempt.state), "Sign-in state did not match. Try again.")
        parameters["error"]?.let {
            throw ChatGptAuthException(if (it == "access_denied") "ChatGPT sign-in was declined. You can try again to enable plan usage." else "ChatGPT authorization failed. Try again.")
        }
        val code = parameters["code"].orEmpty()
        checkAuth(code.isNotBlank(), "Sign-in callback did not contain an authorization code.")
        val client = parameters["client_id"] ?: attempt.clientId
        checkAuth(client.isNotBlank() && client != DYNAMIC_CLIENT, "ChatGPT registration is incomplete. Try again.")
        checkAuth(attempt.clientId == DYNAMIC_CLIENT || client == attempt.clientId, "ChatGPT returned a different account registration.")
        return OAuthCallback(code, client)
    }

    fun parseModels(body: String): List<DiscoveredModel> {
        val array = JSONObject(body).optJSONArray("models") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val model = array.optJSONObject(index) ?: return@mapNotNull null
            val slug = model.optString("slug")
            if (model.optString("visibility") != "list" || slug.isBlank()) null
            else DiscoveredModel(slug, model.optString("display_name").ifBlank { slug })
        }.distinctBy(DiscoveredModel::id)
    }

    fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
        left.toByteArray(Charsets.UTF_8), right.toByteArray(Charsets.UTF_8),
    )
}

// These classes intentionally have no generated toString that could expose secrets.
internal class OAuthAttempt(
    val redirectUri: String,
    val clientId: String,
    val state: String = ChatGptOAuth.randomValue(),
    val nonce: String = ChatGptOAuth.randomValue(),
    val verifier: String = ChatGptOAuth.randomValue(),
)

internal class OAuthCallback(val code: String, val clientId: String)
internal data class VerifiedChatGptIdentity(val subject: String, val email: String?)

class ChatGptAuthException(message: String) : Exception(message)

internal fun checkAuth(condition: Boolean, message: String) {
    if (!condition) throw ChatGptAuthException(message)
}

internal data class AuthHttpResponse(val status: Int, val body: String) {
    override fun toString() = "AuthHttpResponse(status=$status)"
}

internal fun interface AuthHttpClient {
    fun request(url: String, form: Map<String, String>?, bearer: String?): AuthHttpResponse
}

internal class UrlConnectionAuthHttpClient : AuthHttpClient {
    override fun request(url: String, form: Map<String, String>?, bearer: String?): AuthHttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            bearer?.let { setRequestProperty("Authorization", "Bearer $it") }
            if (form != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        try {
            form?.let { values -> connection.outputStream.use { it.write(ChatGptOAuth.form(values).toByteArray(Charsets.UTF_8)) } }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            // Bound even error responses, and never include a response body in an exception.
            val body = stream?.use {
                val bytes = it.readBytesBounded(1_048_576)
                bytes.toString(Charsets.UTF_8)
            }.orEmpty()
            return AuthHttpResponse(status, body)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        checkAuth(output.size() + count <= limit, "ChatGPT returned an oversized response.")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal class ChatGptIdTokenVerifier(private val http: AuthHttpClient, private val now: () -> Long) {
    private var cachedKeys: JWKSet? = null
    private var keysSavedAt = 0L

    @Synchronized
    fun verify(token: String, clientId: String, nonce: String?, expectedSubject: String?): VerifiedChatGptIdentity {
        return try {
            val jwt = SignedJWT.parse(token)
            checkAuth(jwt.header.algorithm == JWSAlgorithm.RS256, "Unsupported ChatGPT identity signature.")
            val kid = jwt.header.keyID
            checkAuth(!kid.isNullOrBlank(), "ChatGPT identity signature key is missing.")
            if (cachedKeys == null || now() - keysSavedAt > 3_600_000L) fetchKeys()
            if (cachedKeys?.getKeyByKeyId(kid) == null) fetchKeys()
            val key = cachedKeys?.getKeyByKeyId(kid) as? RSAKey
            checkAuth(key != null && !key.isPrivate && (key.keyUse == null || key.keyUse == KeyUse.SIGNATURE) &&
                (key.algorithm == null || key.algorithm == JWSAlgorithm.RS256), "ChatGPT identity signature key is invalid.")
            checkAuth(jwt.verify(RSASSAVerifier(key!!.toRSAPublicKey())), "ChatGPT identity signature is invalid.")
            val claims = jwt.jwtClaimsSet
            checkAuth(claims.issuer == ChatGptOAuth.ISSUER, "ChatGPT identity issuer did not match.")
            checkAuth(claims.audience.contains(clientId), "ChatGPT identity audience did not match.")
            if (claims.audience.size > 1 || claims.getStringClaim("azp") != null) {
                checkAuth(claims.getStringClaim("azp") == clientId, "ChatGPT identity authorized party did not match.")
            }
            val time = now()
            checkAuth(claims.expirationTime != null && claims.expirationTime.time > time - 5_000L, "ChatGPT identity has expired.")
            checkAuth(claims.issueTime != null && claims.issueTime.time <= time + 5_000L, "ChatGPT identity issue time is invalid.")
            checkAuth(claims.notBeforeTime == null || claims.notBeforeTime.time <= time + 5_000L, "ChatGPT identity is not valid yet.")
            if (nonce != null) checkAuth(ChatGptOAuth.constantTimeEquals(claims.getStringClaim("nonce").orEmpty(), nonce), "ChatGPT identity nonce did not match.")
            val subject = claims.subject
            checkAuth(!subject.isNullOrBlank(), "ChatGPT identity is missing its account ID.")
            checkAuth(expectedSubject == null || subject == expectedSubject, "A different ChatGPT account signed in. Add it as a new account instead.")
            VerifiedChatGptIdentity(subject, claims.getStringClaim("email"))
        } catch (failure: ChatGptAuthException) {
            throw failure
        } catch (_: Exception) {
            throw ChatGptAuthException("Could not verify the ChatGPT identity. Try signing in again.")
        }
    }

    private fun fetchKeys() {
        val response = http.request("${ChatGptOAuth.ISSUER}/.well-known/jwks.json", null, null)
        checkAuth(response.status == 200, "Could not fetch ChatGPT identity verification keys.")
        cachedKeys = JWKSet.parse(response.body)
        keysSavedAt = now()
    }
}
