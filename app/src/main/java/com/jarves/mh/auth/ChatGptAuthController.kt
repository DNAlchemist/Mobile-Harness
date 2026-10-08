package com.jarves.mh.auth

import android.content.Context
import com.jarves.mh.network.DiscoveredModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class ChatGptSavedAccount(val clientId: String, val label: String, val connected: Boolean)

data class ChatGptAuthState(
    val busy: Boolean = false,
    val connected: Boolean = false,
    val email: String? = null,
    val planEnabled: Boolean = false,
    val error: String? = null,
    val models: List<DiscoveredModel> = emptyList(),
    val savedAccounts: List<ChatGptSavedAccount> = emptyList(),
    val activeAccountId: String? = null,
)

/** Own this in a ViewModel; call cancelSignIn from onCleared. No Activity is retained. */
class ChatGptAuthController internal constructor(
    private val store: ChatGptSessionStore,
    private val http: AuthHttpClient,
    private val now: () -> Long = System::currentTimeMillis,
    private val browserDispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    constructor(context: Context) : this(EncryptedChatGptSessionStore(context.applicationContext), UrlConnectionAuthHttpClient())

    private val mutableState = MutableStateFlow(ChatGptAuthState())
    val state: StateFlow<ChatGptAuthState> = mutableState.asStateFlow()
    private val credentialMutex = Mutex()
    private var sessions: ChatGptSessions? = null
    private val verifier = ChatGptIdTokenVerifier(http, now)
    private val signInJob = AtomicReference<Job?>()
    private val callbackListener = AtomicReference<ChatGptLoopbackListener?>()
    private val signingOut = AtomicBoolean(false)
    private val activeOperations = AtomicInteger()
    private val revision = AtomicLong()

    suspend fun restore() = withContext(Dispatchers.IO) {
        try {
            credentialMutex.withLock { load(); publish() }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            mutableState.update { it.copy(error = friendly(failure)) }
        }
    }

    suspend fun signIn(openBrowser: (String) -> Unit) = authorize(false, openBrowser)

    suspend fun signInNewAccount(openBrowser: (String) -> Unit) = authorize(true, openBrowser)

    private suspend fun authorize(newAccount: Boolean, openBrowser: (String) -> Unit) = withContext(Dispatchers.IO) {
        val job = currentCoroutineContext().job
        if (signingOut.get()) return@withContext
        if (!signInJob.compareAndSet(null, job)) return@withContext
        val attemptRevision = revision.get()
        activeOperations.incrementAndGet()
        mutableState.update { it.copy(busy = true, error = null) }
        try {
            val (hostId, selected) = credentialMutex.withLock {
                val loaded = load()
                loaded.hostId to if (newAccount) null else loaded.activeAccount()
            }
            // Binding happens before the authorization URL is handed to the browser.
            val listener = ChatGptLoopbackListener()
            callbackListener.set(listener)
            val attempt = OAuthAttempt(listener.redirectUri, selected?.clientId ?: ChatGptOAuth.DYNAMIC_CLIENT)
            withContext(browserDispatcher) { openBrowser(ChatGptOAuth.authorizationUrl(attempt, hostId, selected)) }
            val callback = listener.awaitCallback(attempt)
            currentCoroutineContext().ensureActive()
            // Retain an issued client even when code exchange fails (e.g. invalid_grant).
            if (selected == null) credentialMutex.withLock {
                checkAuth(revision.get() == attemptRevision, "ChatGPT sign-in was cancelled.")
                val loaded = load()
                if (loaded.accounts.none { it.clientId == callback.clientId }) {
                    save(loaded.replace(ChatGptAccount(callback.clientId), activate = loaded.activeClientId == null))
                }
            }
            val response = http.request(ChatGptOAuth.TOKEN, linkedMapOf(
                "grant_type" to "authorization_code",
                "client_id" to callback.clientId,
                "code" to callback.code,
                "code_verifier" to attempt.verifier,
                "redirect_uri" to attempt.redirectUri,
                "resource" to ChatGptOAuth.RESOURCE,
            ), null)
            val tokens = parseTokenResponse(response, null)
            val expectedSubject = credentialMutex.withLock {
                load().accounts.firstOrNull { it.clientId == callback.clientId }?.subject
            }
            val identity = verifier.verify(tokens.idToken, callback.clientId, attempt.nonce, expectedSubject)
            currentCoroutineContext().ensureActive()
            credentialMutex.withLock {
                checkAuth(revision.get() == attemptRevision, "ChatGPT sign-in was cancelled.")
                save(load().replace(ChatGptAccount(callback.clientId, identity.subject, identity.email, tokens), activate = true))
                revision.incrementAndGet()
                publish(models = emptyList(), error = if (ChatGptOAuth.PLAN_SCOPE !in tokens.scopes) {
                    "Signed in, but ChatGPT plan usage was not enabled. Continue with ChatGPT again to review permissions."
                } else null)
            }
            if (ChatGptOAuth.PLAN_SCOPE in tokens.scopes) listModels()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(error = friendly(failure)) }
        } finally {
            callbackListener.getAndSet(null)?.close()
            signInJob.compareAndSet(job, null)
            activeOperations.decrementAndGet()
            mutableState.update { it.copy(busy = activeOperations.get() > 0) }
        }
    }

    fun cancelSignIn() {
        revision.incrementAndGet()
        callbackListener.getAndSet(null)?.close()
        signInJob.get()?.cancel()
    }

    /** Returns only an in-memory bearer; callers must never persist or log it. */
    suspend fun validAccessToken(expectedAccountId: String? = null): String = withContext(Dispatchers.IO) {
        credentialMutex.withLock {
            checkAuth(expectedAccountId == null || load().activeClientId == expectedAccountId,
                "The selected ChatGPT account changed. Try the request again.")
            accessToken()
        }
    }

    private fun accessToken(): String {
        val loaded = load()
        val account = loaded.activeAccount() ?: throw ChatGptAuthException("Continue with ChatGPT first.")
        val token = account.tokens ?: throw ChatGptAuthException("This ChatGPT account needs to sign in again.")
        checkAuth(ChatGptOAuth.PLAN_SCOPE in token.scopes, "ChatGPT plan usage is not enabled. Continue with ChatGPT to grant access.")
        val time = now()
        if (token.expiresAt > time + 60_000L || (time < token.earliestRefreshAt && token.expiresAt > time)) return token.accessToken
        checkAuth(time >= token.earliestRefreshAt, "ChatGPT requested a later token refresh. Try again shortly.")
        val refresh = token.refreshToken ?: throw ChatGptAuthException("This ChatGPT account needs to sign in again.")
        val response = http.request(ChatGptOAuth.TOKEN, linkedMapOf(
            "grant_type" to "refresh_token", "client_id" to account.clientId,
            "refresh_token" to refresh, "resource" to ChatGptOAuth.RESOURCE,
        ), null)
        if (response.status !in 200..299 && oauthError(response) == "invalid_grant") {
            save(loaded.replace(account.withoutTokens()))
            publish(error = "The ChatGPT session expired or was revoked. Continue with ChatGPT again.")
            throw ChatGptAuthException("The ChatGPT session expired or was revoked. Continue with ChatGPT again.")
        }
        val replacement = parseTokenResponse(response, token)
        // Refresh responses may omit the ID token; if supplied, verify it before replacing credentials.
        val identity = if (replacement.idToken != token.idToken) verifier.verify(replacement.idToken, account.clientId, null, account.subject) else null
        val updated = ChatGptAccount(account.clientId, account.subject, identity?.email ?: account.email, replacement)
        save(loaded.replace(updated))
        publish()
        checkAuth(ChatGptOAuth.PLAN_SCOPE in replacement.scopes, "ChatGPT plan access was removed. Continue with ChatGPT again.")
        return replacement.accessToken
    }

    suspend fun listModels(): List<DiscoveredModel> = withContext(Dispatchers.IO) {
        try {
            // Keep the selected registration fixed through discovery and publication.
            credentialMutex.withLock {
                val token = accessToken()
                val response = http.request("${ChatGptOAuth.RESOURCE}/models", null, token)
                checkAuth(response.status == 200, when (response.status) {
                    401 -> "ChatGPT rejected this session. Continue with ChatGPT again."
                    403 -> "This ChatGPT account does not have permission to list models."
                    429 -> "ChatGPT usage limit reached. Check your ChatGPT usage and try again later."
                    else -> "Could not load ChatGPT models. Try refreshing again."
                })
                val models = ChatGptOAuth.parseModels(response.body)
                publish(models = models, error = if (models.isEmpty()) "No models are available for this ChatGPT account yet." else null)
                models
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutableState.update { it.copy(error = friendly(failure)) }
            emptyList()
        }
    }

    suspend fun selectAccount(clientId: String) = withContext(Dispatchers.IO) {
        cancelSignIn()
        credentialMutex.withLock {
            val loaded = load()
            checkAuth(loaded.accounts.any { it.clientId == clientId }, "The saved ChatGPT account was not found.")
            revision.incrementAndGet()
            save(ChatGptSessions(loaded.hostId, clientId, loaded.accounts))
            publish(models = emptyList(), error = null)
        }
        if (state.value.planEnabled) listModels()
    }

    /** Clear tokens even if remote revocation cannot be confirmed; keep host and account mapping. */
    suspend fun signOut() = withContext(NonCancellable + Dispatchers.IO) {
        if (!signingOut.compareAndSet(false, true)) return@withContext
        activeOperations.incrementAndGet()
        mutableState.update { it.copy(busy = true) }
        try {
            cancelSignIn()
            credentialMutex.withLock {
                revision.incrementAndGet()
                val loaded = load()
                val account = loaded.activeAccount() ?: return@withLock
                val revoked = revoke(account)
                save(loaded.replace(account.withoutTokens()))
                publish(models = emptyList(), error = if (revoked) null else "Signed out on this device. Remote revocation was not confirmed; you can disconnect this app in ChatGPT Settings.")
            }
        } finally {
            signingOut.set(false)
            activeOperations.decrementAndGet()
            mutableState.update { it.copy(busy = activeOperations.get() > 0) }
        }
    }

    private suspend fun revoke(account: ChatGptAccount): Boolean {
        val refresh = account.tokens?.refreshToken ?: return true
        return try {
            val discovery = http.request(ChatGptOAuth.DISCOVERY, null, null)
            checkAuth(discovery.status == 200, "Could not load ChatGPT sign-out configuration.")
            val config = JSONObject(discovery.body)
            checkAuth(config.optString("issuer") == ChatGptOAuth.ISSUER, "Invalid ChatGPT sign-out configuration.")
            val endpoint = config.getString("revocation_endpoint")
            val uri = URI(endpoint)
            checkAuth(uri.scheme == "https" && uri.host == "auth.openai.com" && uri.userInfo == null && uri.port == -1, "Invalid ChatGPT revocation endpoint.")
            for (index in 0..1) {
                val response = runCatching { http.request(endpoint, mapOf(
                    "token" to refresh, "token_type_hint" to "refresh_token", "client_id" to account.clientId,
                ), null) }.getOrNull()
                if (response?.status == 200) return true
                if (response != null && response.status < 500) return false
                if (index == 0) delay(1000)
            }
            false
        } catch (_: Exception) { false }
    }

    private fun load(): ChatGptSessions = sessions ?: store.read().also { sessions = it }

    private fun save(value: ChatGptSessions) {
        store.write(value)
        sessions = value
    }

    private fun publish(models: List<DiscoveredModel> = state.value.models, error: String? = state.value.error) {
        val loaded = sessions ?: return
        val account = loaded.activeAccount()
        mutableState.update { previous -> previous.copy(
            connected = account?.tokens != null,
            email = account?.email,
            planEnabled = account?.tokens?.scopes?.contains(ChatGptOAuth.PLAN_SCOPE) == true,
            error = error,
            models = if (account?.tokens?.scopes?.contains(ChatGptOAuth.PLAN_SCOPE) == true) models else emptyList(),
            activeAccountId = loaded.activeClientId,
            savedAccounts = loaded.accounts.map {
                ChatGptSavedAccount(it.clientId, "${it.email ?: "ChatGPT account"} · ${it.clientId.takeLast(6)}", it.tokens != null)
            },
        ) }
    }

    private fun parseTokenResponse(response: AuthHttpResponse, previous: ChatGptTokens?): ChatGptTokens {
        checkAuth(response.status == 200, when (oauthError(response)) {
            "invalid_grant" -> "The sign-in code expired. Continue with ChatGPT again; your registration was retained."
            "access_denied", "invalid_scope" -> "ChatGPT did not grant the requested access. Continue with ChatGPT again."
            else -> if (response.status == 429) "ChatGPT is rate limited. Try again shortly." else "ChatGPT token exchange failed. Try signing in again."
        })
        val json = JSONObject(response.body)
        checkAuth(json.optString("token_type").equals("Bearer", ignoreCase = true), "Unsupported ChatGPT token type.")
        val access = json.optString("access_token")
        val refresh = json.optionalString("refresh_token")
        val idToken = json.optionalString("id_token") ?: previous?.idToken
        val expiresIn = json.optLong("expires_in", 0)
        checkAuth(access.isNotBlank() && idToken != null && expiresIn in 1..86_400, "ChatGPT returned incomplete credentials.")
        // A rotating refresh must be replaced atomically; never reuse an old one after success.
        checkAuth(previous == null || refresh != null, "ChatGPT did not return a replacement refresh token. Sign in again.")
        val scopes = if (json.has("scope")) json.optString("scope").split(' ').filter(String::isNotBlank).toSet() else previous?.scopes.orEmpty()
        checkAuth("offline_access" !in scopes || refresh != null, "ChatGPT did not return renewable credentials. Sign in again.")
        val earliest = json.optLong("earliest_refresh_at", 0)
        return ChatGptTokens(access, refresh, idToken!!, scopes, now() + expiresIn * 1000L, earliest * 1000L)
    }

    private fun oauthError(response: AuthHttpResponse): String? = runCatching { JSONObject(response.body).optionalString("error") }.getOrNull()

    private fun friendly(failure: Exception): String = if (failure is ChatGptAuthException) failure.message.orEmpty()
        else "Could not connect to ChatGPT. Check your connection and try again."
}
