package com.jarves.mh.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class ChatGptTokens(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String,
    val scopes: Set<String>,
    val expiresAt: Long,
    val earliestRefreshAt: Long = 0L,
)

internal class ChatGptAccount(
    val clientId: String,
    val subject: String? = null,
    val email: String? = null,
    val tokens: ChatGptTokens? = null,
) {
    fun withoutTokens() = ChatGptAccount(clientId, subject, email)
}

internal class ChatGptSessions(
    val hostId: String = "urn:uuid:${UUID.randomUUID()}",
    val activeClientId: String? = null,
    val accounts: List<ChatGptAccount> = emptyList(),
) {
    fun activeAccount() = accounts.firstOrNull { it.clientId == activeClientId }

    fun replace(account: ChatGptAccount, activate: Boolean = false) = ChatGptSessions(
        hostId,
        if (activate) account.clientId else activeClientId,
        accounts.filterNot { it.clientId == account.clientId } + account,
    )
}

internal interface ChatGptSessionStore {
    fun read(): ChatGptSessions
    fun write(sessions: ChatGptSessions)
}

/** One encrypted atomic record in Android's private, backup-excluded storage. */
internal class EncryptedChatGptSessionStore(context: Context) : ChatGptSessionStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-sessions.enc"))
    private val alias = "mobile-harness-chatgpt-sessions-v1"

    override fun read(): ChatGptSessions {
        if (!file.baseFile.exists()) return ChatGptSessions().also(::write)
        try {
            val bytes = file.openRead().use { it.readBytesBounded(1_048_576) }
            checkAuth(bytes.size > 12, "Saved ChatGPT credentials are incomplete.")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return ChatGptSessionCodec.decode(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        } catch (_: Exception) {
            // Never silently generate a new host or overwrite unreadable credentials.
            throw ChatGptAuthException("The saved ChatGPT connection could not be unlocked on this device.")
        }
    }

    override fun write(sessions: ChatGptSessions) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(ChatGptSessionCodec.encode(sessions).toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try {
            stream.write(cipher.iv)
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw ChatGptAuthException("Could not securely save the ChatGPT connection.")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            generateKey()
        }
    }
}

internal object ChatGptSessionCodec {
    fun encode(sessions: ChatGptSessions): String = JSONObject()
        .put("host_id", sessions.hostId)
        .put("active_client_id", sessions.activeClientId)
        .put("accounts", JSONArray().also { array ->
            sessions.accounts.forEach { account ->
                array.put(JSONObject().put("client_id", account.clientId).put("subject", account.subject).put("email", account.email)
                    .also { item -> account.tokens?.let { token -> item.put("tokens", JSONObject()
                        .put("access_token", token.accessToken).put("refresh_token", token.refreshToken).put("id_token", token.idToken)
                        .put("scope", token.scopes.joinToString(" ")).put("expires_at", token.expiresAt)
                        .put("earliest_refresh_at", token.earliestRefreshAt)) } })
            }
        }).toString()

    fun decode(json: String): ChatGptSessions {
        val root = JSONObject(json)
        val hostId = root.getString("host_id")
        checkAuth(hostId.startsWith("urn:uuid:"), "Invalid saved ChatGPT host.")
        UUID.fromString(hostId.removePrefix("urn:uuid:"))
        val array = root.getJSONArray("accounts")
        val accounts = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val clientId = item.getString("client_id")
            checkAuth(clientId.isNotBlank() && clientId != ChatGptOAuth.DYNAMIC_CLIENT, "Invalid saved ChatGPT registration.")
            ChatGptAccount(clientId, item.optionalString("subject"), item.optionalString("email"), item.optJSONObject("tokens")?.let {
                ChatGptTokens(it.getString("access_token"), it.optionalString("refresh_token"), it.getString("id_token"),
                    it.getString("scope").split(' ').filter(String::isNotBlank).toSet(), it.getLong("expires_at"), it.optLong("earliest_refresh_at"))
            })
        }
        checkAuth(accounts.distinctBy { it.clientId }.size == accounts.size, "Duplicate saved ChatGPT registrations.")
        return ChatGptSessions(hostId, root.optionalString("active_client_id"), accounts)
    }
}

internal fun JSONObject.optionalString(key: String): String? = if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)
