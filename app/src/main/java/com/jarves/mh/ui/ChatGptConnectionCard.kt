package com.jarves.mh.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarves.mh.auth.ChatGptAuthState
import com.jarves.mh.network.ConnectionValidation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

data class ChatGptActions(
    val signIn: () -> Unit = {},
    val addAccount: () -> Unit = {},
    val cancel: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val refreshModels: () -> Unit = {},
    val selectAccount: (String) -> Unit = {},
)

@Composable
internal fun ChatGptConnectionCard(
    auth: ChatGptAuthState,
    model: String,
    onModel: (String) -> Unit,
    actions: ChatGptActions,
    onValidate: suspend (String) -> ConnectionValidation,
    onSave: (String) -> Unit,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var result by remember(auth.activeAccountId, model) { mutableStateOf<ConnectionValidation?>(null) }
    LaunchedEffect(auth.activeAccountId, auth.models) {
        if (auth.models.isNotEmpty() && auth.models.none { it.id == model }) onModel(auth.models.first().id)
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text("ChatGPT", style = MaterialTheme.typography.titleLarge)
        Text(
            if (auth.connected && auth.planEnabled) "Using ChatGPT plan" else "Connect your ChatGPT account to use its available plan allowance.",
            style = MaterialTheme.typography.bodyMedium,
        )
        auth.email?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (auth.connected && !auth.planEnabled) {
            Text("Signed in for identity only. Continue with ChatGPT again and grant plan access to run tasks.")
        }
        auth.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (auth.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Updating your ChatGPT connection. Complete any browser sign-in, then return here.")
            OutlinedButton(onClick = actions.cancel, enabled = enabled) { Text("Cancel sign-in") }
        } else {
            if (!auth.connected || !auth.planEnabled) {
                Button(onClick = actions.signIn, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue with ChatGPT")
                }
            } else {
                TextButton(onClick = actions.signIn, enabled = enabled && !testing) { Text("Reconnect ChatGPT") }
            }
            if (auth.savedAccounts.isNotEmpty()) {
                auth.savedAccounts.forEach { account ->
                    OutlinedButton(
                        onClick = { actions.selectAccount(account.clientId) },
                        enabled = enabled && !testing && account.clientId != auth.activeAccountId,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(account.label + if (account.clientId == auth.activeAccountId) " · selected" else "") }
                }
                TextButton(onClick = actions.addAccount, enabled = enabled && !testing) { Text("Add ChatGPT account") }
            }
        }
        if (auth.connected && auth.planEnabled) {
            Text("Available models", style = MaterialTheme.typography.titleMedium)
            auth.models.forEach { available ->
                if (available.id == model) {
                    Button(onClick = {}, enabled = enabled && !testing, modifier = Modifier.fillMaxWidth()) {
                        Text("✓ ${available.displayName}")
                    }
                } else {
                    OutlinedButton(onClick = { onModel(available.id) }, enabled = enabled && !testing, modifier = Modifier.fillMaxWidth()) {
                        Text(available.displayName)
                    }
                }
            }
            TextButton(onClick = actions.refreshModels, enabled = enabled && !auth.busy && !testing) { Text("Refresh available models") }
            Button(
                onClick = {
                    scope.launch {
                        testing = true
                        result = null
                        try {
                            val checked = onValidate(model)
                            result = checked
                            if (checked is ConnectionValidation.Success) onSave(model)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            result = ConnectionValidation.Failure("Could not test ChatGPT. Try again.")
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = enabled && !auth.busy && !testing && auth.models.any { it.id == model },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (testing) "Testing ChatGPT…" else "Test connection and save") }
            Text("The test sends a short request and uses your ChatGPT plan allowance.", style = MaterialTheme.typography.bodySmall)
        }
        result?.let { checked ->
            Text(
                when (checked) {
                    is ConnectionValidation.Success -> checked.message
                    is ConnectionValidation.Failure -> checked.message
                },
                color = if (checked is ConnectionValidation.Failure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage")))
        }) { Text("Manage ChatGPT usage") }
        if (auth.connected) {
            TextButton(onClick = actions.disconnect, enabled = enabled && !testing && !auth.busy) { Text("Disconnect ChatGPT") }
        }
        Text("Community integration. Model availability and usage depend on your ChatGPT account.", style = MaterialTheme.typography.bodySmall)
    }
}
