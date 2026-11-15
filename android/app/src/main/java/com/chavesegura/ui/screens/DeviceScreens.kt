@file:OptIn(ExperimentalMaterial3Api::class)

package com.chavesegura.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chavesegura.AppContainer
import com.chavesegura.authorization.ApprovalRequest
import com.chavesegura.domain.AuthorizationMode
import com.chavesegura.domain.DeviceStatus
import com.chavesegura.domain.TrustedDevice
import com.chavesegura.pairing.PairingException
import com.chavesegura.pairing.PendingPairing
import com.chavesegura.pairing.QrPayload
import com.chavesegura.ui.AppTopBar
import com.chavesegura.ui.BigButton
import com.chavesegura.ui.BigOutlinedButton
import com.chavesegura.ui.ErrorText
import com.chavesegura.ui.Hint
import com.chavesegura.ui.Navigator
import com.chavesegura.ui.Screen
import com.chavesegura.ui.SectionCard
import com.chavesegura.ui.SuccessColor
import com.chavesegura.ui.formatHour
import com.chavesegura.ui.formatTime
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

// ------------------------------------------------ computadores pareados ---

@Composable
fun DevicesScreen(container: AppContainer, navigator: Navigator) {
    val devices by remember { container.devices.observe() }.collectAsState(initial = emptyList())
    val online by container.coordinator.online.collectAsStateWithLifecycle()
    val connected by container.coordinator.connected.collectAsStateWithLifecycle()
    val trust by container.coordinator.trustSessions.collectAsStateWithLifecycle()
    var toRevoke by remember { mutableStateOf<TrustedDevice?>(null) }
    var toAutomatic by remember { mutableStateOf<TrustedDevice?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Computadores")
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            BigButton("Parear computador", { navigator.push(Screen.Pair) })
            Hint("Deixe este app aberto e o cofre desbloqueado para atender os pedidos do computador.")
            val active = devices.filter { it.status == DeviceStatus.ACTIVE }
            if (active.isEmpty()) Hint("Nenhum computador conectado ainda.")
            active.forEach { device ->
                SectionCard {
                    Text("💻 ${device.deviceName}", style = MaterialTheme.typography.titleMedium)
                    val status = when {
                        device.id in online -> "Extensão on-line"
                        device.id in connected -> "Aguardando a extensão"
                        else -> "Sem conexão com o servidor"
                    }
                    Hint("$status · pareado em ${formatTime(device.createdAt)}")
                    Text("Como liberar as senhas:", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    ModeOption(
                        selected = device.authorizationMode == AuthorizationMode.APPROVAL,
                        title = "Confirmar no celular (recomendado)",
                        detail = "Cada pedido aparece aqui para você aprovar.",
                    ) { scope.launch { container.coordinator.setMode(device.id, AuthorizationMode.APPROVAL) } }
                    ModeOption(
                        selected = device.authorizationMode == AuthorizationMode.AUTOMATIC,
                        title = "Automático neste computador",
                        detail = "Preenche sem perguntar por um tempo limitado. Contas sensíveis continuam pedindo confirmação.",
                    ) { if (device.authorizationMode != AuthorizationMode.AUTOMATIC) toAutomatic = device }
                    if (device.authorizationMode == AuthorizationMode.AUTOMATIC) {
                        val until = trust[device.id]
                        if (until != null) {
                            Text("Automático até ${formatHour(until)}", color = SuccessColor)
                        } else {
                            Text("Confiança expirada: os pedidos voltam a pedir confirmação.", color = MaterialTheme.colorScheme.error)
                        }
                        TextButton({ container.coordinator.renewTrust(device.id) }) { Text("Renovar confiança") }
                    }
                    BigOutlinedButton("Desconectar este computador", { toRevoke = device })
                }
            }
            val revoked = devices.filter { it.status == DeviceStatus.REVOKED }
            if (revoked.isNotEmpty()) {
                Text("Desconectados", style = MaterialTheme.typography.titleMedium)
                revoked.forEach { device ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(device.deviceName)
                            Hint(if (device.pendingRevocation) "Será avisado ao servidor na próxima conexão" else "Revogado")
                        }
                        if (!device.pendingRevocation) {
                            TextButton({ scope.launch { container.devices.delete(device.id) } }) { Text("Remover") }
                        }
                    }
                }
            }
        }
    }

    toAutomatic?.let { device ->
        AlertDialog(
            onDismissRequest = { toAutomatic = null },
            title = { Text("Ligar o modo automático?") },
            text = {
                Text(
                    "“${device.deviceName}” poderá preencher senhas sem pedir confirmação por " +
                        "${container.settings.trustMinutes} minutos, enquanto o cofre estiver aberto. " +
                        "Use só no seu computador pessoal.",
                )
            },
            confirmButton = {
                TextButton({
                    scope.launch { container.coordinator.setMode(device.id, AuthorizationMode.AUTOMATIC) }
                    toAutomatic = null
                }) { Text("Ligar") }
            },
            dismissButton = { TextButton({ toAutomatic = null }) { Text("Cancelar") } },
        )
    }

    toRevoke?.let { device ->
        AlertDialog(
            onDismissRequest = { toRevoke = null },
            title = { Text("Desconectar computador?") },
            text = { Text("“${device.deviceName}” não poderá mais pedir senhas. Para usar de novo, será preciso parear outra vez.") },
            confirmButton = {
                TextButton({
                    scope.launch { container.coordinator.revoke(device.id) }
                    toRevoke = null
                }) { Text("Desconectar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton({ toRevoke = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ModeOption(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Hint(detail)
        }
    }
}

// ----------------------------------------------------------- pareamento ---

@Composable
fun PairScreen(container: AppContainer, navigator: Navigator) {
    var qr by remember { mutableStateOf<QrPayload?>(null) }
    var pending by remember { mutableStateOf<PendingPairing?>(null) }
    var pasted by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) { onDispose { pending?.wipe() } }

    fun accept(text: String) {
        val parsed = QrPayload.parse(text)
        if (parsed == null) {
            error = "Este não é um código de pareamento do Chave Segura."
        } else {
            error = null
            qr = parsed
        }
    }

    val scanner = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(::accept)
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar("Parear computador", onBack = navigator::pop)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val current = qr
            val claimed = pending
            when {
                done != null -> {
                    Text("✅ Pronto!", style = MaterialTheme.typography.headlineMedium)
                    Text("“$done” está conectado. Ele começa no modo de confirmação: cada pedido aparecerá aqui.")
                    BigButton("Concluir", navigator::pop)
                }

                claimed != null -> {
                    Text("Confira o código", style = MaterialTheme.typography.titleLarge)
                    Text("O computador “${claimed.qr.computerName}” deve mostrar o mesmo número:")
                    Text(
                        "${claimed.sas.take(3)} ${claimed.sas.drop(3)}",
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Se os números forem diferentes, alguém pode estar tentando se passar pelo seu computador.")
                    ErrorText(error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    BigButton("Os números são iguais — conectar", enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val linkId = container.pairing.confirm(claimed, accepted = true)
                                if (linkId != null) {
                                    container.devices.addFromPairing(claimed, linkId)
                                    container.coordinator.stop()
                                    container.coordinator.start()
                                    done = claimed.qr.computerName
                                }
                            } catch (e: PairingException) {
                                error = e.message
                            } finally {
                                claimed.wipe()
                                pending = null
                                busy = false
                            }
                        }
                    })
                    BigOutlinedButton("São diferentes — cancelar", enabled = !busy, onClick = {
                        scope.launch {
                            runCatching { container.pairing.confirm(claimed, accepted = false) }
                            claimed.wipe()
                            pending = null
                            qr = null
                            error = "Pareamento cancelado."
                        }
                    })
                }

                current != null -> {
                    Text("Conectar a este computador?", style = MaterialTheme.typography.titleLarge)
                    SectionCard {
                        Hint("Computador")
                        Text(current.computerName, style = MaterialTheme.typography.titleMedium)
                        Hint("Servidor: ${current.httpBase}")
                    }
                    Hint("Só continue se foi você quem pediu o pareamento neste computador.")
                    ErrorText(error)
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    BigButton("Continuar", enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                pending = container.pairing.claim(current, container.settings.deviceName)
                                error = null
                            } catch (e: PairingException) {
                                error = e.message
                            } finally {
                                busy = false
                            }
                        }
                    })
                    BigOutlinedButton("Cancelar", { qr = null })
                }

                else -> {
                    Text("No computador, abra a extensão Chave Segura e clique em “Parear com o celular”.")
                    BigButton("Ler QR Code com a câmera", {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Aponte para o QR Code da extensão")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    })
                    Text("Sem câmera? Cole o código", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = pasted,
                        onValueChange = { pasted = it },
                        label = { Text("Código copiado na extensão") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    BigOutlinedButton("Usar código colado", { accept(pasted) }, enabled = pasted.isNotBlank())
                    ErrorText(error)
                }
            }
        }
    }
}

// ----------------------------------------------------------- aprovação ---

/** Pedido de preenchimento vindo do computador: o usuário confere e decide. */
@Composable
fun ApprovalDialog(request: ApprovalRequest, onAnswer: (String?) -> Unit) {
    var chosen by remember(request.requestId) { mutableStateOf(request.candidates.singleOrNull()?.id) }
    val sensitive = request.candidates.any { it.id == chosen && it.sensitive }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Liberar senha?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Computador: ${request.deviceName}", style = MaterialTheme.typography.bodyLarge)
                Text("Site: ${request.hostname}", style = MaterialTheme.typography.titleMedium)
                Hint(request.reason.text)
                Hint("Confira se o site é mesmo o que você abriu.")
                request.candidates.forEach { candidate ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = chosen == candidate.id, onClick = { chosen = candidate.id }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = chosen == candidate.id, onClick = { chosen = candidate.id })
                        Column {
                            Text(candidate.title)
                            Hint(candidate.username + if (candidate.sensitive) " · sensível" else "")
                        }
                    }
                }
                if (sensitive) Text("⚠️ Conta sensível. Libere só se você mesmo está entrando agora.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(chosen) }, enabled = chosen != null) { Text("Permitir") } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text("Recusar", color = MaterialTheme.colorScheme.error) } },
    )
}
