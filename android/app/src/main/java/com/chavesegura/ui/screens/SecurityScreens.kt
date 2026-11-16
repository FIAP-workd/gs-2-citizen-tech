@file:OptIn(ExperimentalMaterial3Api::class)

package com.chavesegura.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.chavesegura.AppContainer
import com.chavesegura.domain.DiagnosticReport
import com.chavesegura.domain.Diagnostics
import com.chavesegura.domain.EventTypes
import com.chavesegura.ui.AppTopBar
import com.chavesegura.ui.BigButton
import com.chavesegura.ui.BigOutlinedButton
import com.chavesegura.ui.ErrorText
import com.chavesegura.ui.Hint
import com.chavesegura.ui.Navigator
import com.chavesegura.ui.Screen
import com.chavesegura.ui.SectionCard
import com.chavesegura.ui.SuccessColor
import com.chavesegura.ui.authenticateWithBiometric
import com.chavesegura.ui.formatTime
import kotlinx.coroutines.launch

// ------------------------------------------------------- diagnóstico ---

@Composable
fun SecurityScreen(container: AppContainer, navigator: Navigator) {
    var report by remember { mutableStateOf<DiagnosticReport?>(null) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    LaunchedEffect(Unit) {
        // Calculado em memória após o desbloqueio; nada é gravado.
        val inputs = container.vault.diagnosticInputs()
        titles = inputs.associate { it.credentialId to it.title }
        report = Diagnostics.analyze(inputs)
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Segurança")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigOutlinedButton("Gerador de senhas", { navigator.push(Screen.Generator) })
            BigOutlinedButton("Histórico de segurança", { navigator.push(Screen.Events) })
            Text("Diagnóstico das senhas", style = MaterialTheme.typography.titleLarge)
            val r = report
            when {
                r == null -> Hint("Analisando…")
                titles.isEmpty() -> Hint("Cadastre contas para ver o diagnóstico.")
                r.isHealthy -> Text("✅ Nenhuma senha fraca ou repetida.", color = SuccessColor, style = MaterialTheme.typography.titleMedium)
                else -> {
                    if (r.weak.isNotEmpty()) {
                        SectionCard {
                            Text("⚠️ Senhas fracas (${r.weak.size})", style = MaterialTheme.typography.titleMedium)
                            Hint("Fáceis de adivinhar. Troque por uma senha gerada.")
                            r.weak.forEach { (id, strength) ->
                                Text(
                                    "• ${titles[id]} — ${strength.label}",
                                    modifier = Modifier.fillMaxWidth().clickable { navigator.push(Screen.Detail(id)) }.padding(vertical = 6.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                    if (r.reused.isNotEmpty()) {
                        SectionCard {
                            Text("🔁 Senhas repetidas (${r.reused.size} grupo(s))", style = MaterialTheme.typography.titleMedium)
                            Hint("Se um desses sites vazar, os outros ficam em risco. Use uma senha diferente em cada um.")
                            r.reused.forEach { group ->
                                Text("• " + group.joinToString(", ") { titles[it].orEmpty() }, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------- histórico ---

@Composable
fun EventsScreen(container: AppContainer, navigator: Navigator) {
    val events by remember { container.log.observe() }.collectAsState(initial = emptyList())
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Histórico de segurança", onBack = navigator::pop)
        Hint("  Registra o que aconteceu, sem senhas nem conteúdo das mensagens.")
        LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            items(events, key = { it.id }) { event ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(EventTypes.label(event.eventType), style = MaterialTheme.typography.bodyLarge)
                    Hint("${formatTime(event.timestamp)} · ${event.result}")
                }
                HorizontalDivider()
            }
        }
    }
}

// ------------------------------------------------------------ ajustes ---

@Composable
fun SettingsScreen(container: AppContainer) {
    val activity = LocalContext.current as FragmentActivity
    val scope = rememberCoroutineScope()
    var autoLock by remember { mutableIntStateOf(container.settings.autoLockMinutes) }
    var trust by remember { mutableIntStateOf(container.settings.trustMinutes) }
    var name by remember { mutableStateOf(container.settings.deviceName) }
    var biometricOn by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val biometricAvailable = remember { container.biometric.isAvailable(activity) }

    LaunchedEffect(Unit) { biometricOn = container.vault.hasBiometric() }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Ajustes")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionCard {
                Text("Bloquear o cofre depois de", style = MaterialTheme.typography.titleMedium)
                com.chavesegura.vault.AppSettings.AUTO_LOCK_OPTIONS.forEach { minutes ->
                    Choice("$minutes minuto(s) sem uso", autoLock == minutes) {
                        autoLock = minutes
                        container.settings.autoLockMinutes = minutes
                    }
                }
            }
            SectionCard {
                Text("Duração do modo automático", style = MaterialTheme.typography.titleMedium)
                Hint("Depois disso, o computador volta a pedir confirmação.")
                com.chavesegura.vault.AppSettings.TRUST_OPTIONS.forEach { minutes ->
                    val label = when {
                        minutes == 1 -> "1 minuto (para testes)"
                        minutes < 60 -> "$minutes minutos"
                        else -> "${minutes / 60} hora(s)"
                    }
                    Choice(label, trust == minutes) {
                        trust = minutes
                        container.settings.trustMinutes = minutes
                    }
                }
            }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Desbloquear com biometria", style = MaterialTheme.typography.titleMedium)
                        Hint(
                            if (biometricAvailable) "Atalho para abrir o cofre. A senha mestra continua valendo."
                            else "Este aparelho não tem biometria forte cadastrada.",
                        )
                    }
                    Switch(
                        checked = biometricOn,
                        enabled = biometricAvailable,
                        onCheckedChange = { enable ->
                            error = null
                            if (!enable) {
                                scope.launch { container.vault.disableBiometric(); biometricOn = false }
                            } else {
                                val cipher = runCatching { container.biometric.encryptCipher() }.getOrNull()
                                if (cipher == null) {
                                    error = "Não foi possível preparar a biometria."
                                } else {
                                    authenticateWithBiometric(
                                        activity, "Ativar desbloqueio por biometria", cipher,
                                        onSuccess = { authenticated ->
                                            scope.launch {
                                                container.vault.enableBiometric(authenticated)
                                                biometricOn = true
                                                message = "Biometria ativada."
                                            }
                                        },
                                        onError = { error = it },
                                    )
                                }
                            }
                        },
                    )
                }
            }
            SectionCard {
                Text("Nome deste celular", style = MaterialTheme.typography.titleMedium)
                Hint("Aparece no computador durante o pareamento.")
                OutlinedTextField(name, { name = it.take(64) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                BigOutlinedButton("Salvar nome", {
                    container.settings.deviceName = name.trim().ifEmpty { "Meu celular" }
                    message = "Nome salvo."
                })
            }
            SectionCard {
                Text("Se eu esquecer a senha mestra?", style = MaterialTheme.typography.titleMedium)
                Hint(
                    "O cofre fica só neste celular e é protegido pela sua senha mestra. Não existe senha de suporte " +
                        "nem cópia no servidor. Se você perder o celular E esquecer a senha mestra, as senhas guardadas " +
                        "não podem ser recuperadas. Mantenha também o acesso de recuperação de cada serviço (e-mail, telefone).",
                )
            }
            message?.let { Text(it, color = SuccessColor) }
            ErrorText(error)
            BigButton("Bloquear o cofre agora", { scope.launch { container.vault.lock() } })
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
