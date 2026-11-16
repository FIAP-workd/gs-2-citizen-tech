package com.chavesegura.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.chavesegura.AppContainer
import com.chavesegura.domain.PasswordStrength
import com.chavesegura.security.BiometricInvalidatedException
import com.chavesegura.ui.BigButton
import com.chavesegura.ui.ErrorText
import com.chavesegura.ui.Hint
import com.chavesegura.ui.authenticateWithBiometric
import com.chavesegura.ui.formatHour
import com.chavesegura.vault.UnlockResult
import kotlinx.coroutines.launch

@Composable
fun MasterPasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Ocultar" else "Mostrar") } },
        modifier = modifier.fillMaxWidth(),
        textStyle = MaterialTheme.typography.bodyLarge,
    )
}

/** Criação do cofre: define a senha mestra. */
@Composable
fun SetupScreen(container: AppContainer, onCreated: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val strength = PasswordStrength.estimate(password)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Bem-vindo ao Chave Segura", style = MaterialTheme.typography.headlineMedium)
        Text("Crie uma senha mestra. Ela abre o seu cofre e só você a conhece.", style = MaterialTheme.typography.bodyLarge)
        Hint("Importante: se você esquecer a senha mestra, ninguém consegue recuperar o cofre — nem nós. Anote-a em um lugar seguro.")
        MasterPasswordField(password, { password = it; error = null }, "Senha mestra")
        if (password.isNotEmpty()) Text("Força: ${strength.label}", style = MaterialTheme.typography.bodyLarge)
        MasterPasswordField(confirm, { confirm = it; error = null }, "Repita a senha mestra")
        ErrorText(error)
        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Hint("Protegendo o cofre… isso leva alguns segundos de propósito.")
        }
        BigButton(
            text = "Criar meu cofre",
            enabled = !busy,
            onClick = {
                error = when {
                    password.length < 10 -> "Use pelo menos 10 caracteres."
                    strength.isWeak -> "Essa senha é fácil de adivinhar. Use uma frase com várias palavras."
                    password != confirm -> "As duas senhas não são iguais."
                    else -> null
                }
                if (error == null) {
                    busy = true
                    scope.launch {
                        val chars = password.toCharArray()
                        try {
                            container.vault.create(chars)
                            password = ""
                            confirm = ""
                            onCreated()
                        } catch (e: Exception) {
                            error = "Não foi possível criar o cofre: ${e.message}"
                        } finally {
                            chars.fill('\u0000')
                            busy = false
                        }
                    }
                }
            },
        )
    }
}

/** Desbloqueio por senha mestra ou biometria. */
@Composable
fun UnlockScreen(container: AppContainer) {
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var hasBiometric by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as FragmentActivity

    LaunchedEffect(Unit) {
        hasBiometric = container.vault.hasBiometric() && container.biometric.isAvailable(activity)
    }

    fun unlockWithPassword() {
        if (password.isEmpty()) return
        busy = true
        error = null
        scope.launch {
            val chars = password.toCharArray()
            val result = container.vault.unlock(chars)
            chars.fill('\u0000')
            busy = false
            when (result) {
                UnlockResult.Success -> password = ""
                UnlockResult.WrongPassword -> error = "Senha mestra incorreta. O cofre continua bloqueado."
                is UnlockResult.Throttled -> error = "Muitas tentativas erradas. Tente de novo às ${formatHour(result.retryAtMillis)}."
                UnlockResult.WrongDevice -> error = "Este cofre não pode ser aberto neste aparelho (a chave do aparelho não confere)."
            }
        }
    }

    fun unlockWithBiometric() {
        scope.launch {
            val blob = container.vault.biometricBlob() ?: return@launch
            val cipher = try {
                container.biometric.decryptCipher(blob)
            } catch (e: BiometricInvalidatedException) {
                container.vault.disableBiometric()
                hasBiometric = false
                error = "A biometria do aparelho mudou. Entre com a senha mestra e ative a biometria de novo."
                return@launch
            }
            authenticateWithBiometric(
                activity, "Desbloquear o cofre", cipher,
                onSuccess = { authenticated ->
                    scope.launch {
                        if (container.vault.unlockWithBiometric(authenticated, blob) != UnlockResult.Success) {
                            error = "Não foi possível desbloquear com biometria."
                        }
                    }
                },
                onError = { error = it },
            )
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("🔐 Cofre bloqueado", style = MaterialTheme.typography.headlineMedium)
        Text("Digite a sua senha mestra para abrir o cofre.", style = MaterialTheme.typography.bodyLarge)
        MasterPasswordField(password, { password = it; error = null }, "Senha mestra")
        ErrorText(error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        BigButton("Desbloquear", ::unlockWithPassword, enabled = !busy && password.isNotEmpty())
        if (hasBiometric) {
            com.chavesegura.ui.BigOutlinedButton("Usar biometria", ::unlockWithBiometric, enabled = !busy)
        }
    }
}
