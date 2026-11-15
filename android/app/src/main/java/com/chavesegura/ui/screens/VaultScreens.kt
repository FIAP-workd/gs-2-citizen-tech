@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.chavesegura.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.chavesegura.AppContainer
import com.chavesegura.domain.Credential
import com.chavesegura.domain.GeneratorOptions
import com.chavesegura.domain.PasswordGenerator
import com.chavesegura.domain.PasswordStrength
import com.chavesegura.ui.AppTopBar
import com.chavesegura.ui.BigButton
import com.chavesegura.ui.BigOutlinedButton
import com.chavesegura.ui.ErrorText
import com.chavesegura.ui.Hint
import com.chavesegura.ui.MonoStyle
import com.chavesegura.ui.Navigator
import com.chavesegura.ui.Screen
import com.chavesegura.ui.SectionCard
import com.chavesegura.ui.copyPlain
import com.chavesegura.ui.copySecret
import com.chavesegura.ui.formatTime
import kotlinx.coroutines.launch

// --------------------------------------------------------------- lista ---

@Composable
fun VaultScreen(container: AppContainer, navigator: Navigator) {
    val summaries by remember { container.vault.observeSummaries() }.collectAsState(initial = emptyList())
    val categories by remember { container.vault.observeCategories() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }

    val filtered = summaries.filter { item ->
        val q = query.trim().lowercase()
        val categoryName = categories.firstOrNull { it.id == item.categoryId }?.name.orEmpty()
        (category == null || item.categoryId == category) &&
            (q.isEmpty() || listOf(item.title, item.websiteDomain, item.username, categoryName).any { it.lowercase().contains(q) })
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            AppTopBar("Meu cofre")
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Buscar por nome, site ou categoria") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Todas") })
                categories.forEach { c ->
                    FilterChip(
                        selected = category == c.id,
                        onClick = { category = if (category == c.id) null else c.id },
                        label = { Text("${c.icon} ${c.name}") },
                    )
                }
            }
            if (summaries.isEmpty()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Seu cofre está vazio.", style = MaterialTheme.typography.titleMedium)
                    Hint("Toque em “Nova conta” para guardar sua primeira senha. Você também pode criar senhas fortes direto no computador, pela extensão.")
                }
            }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
                items(filtered, key = { it.id }) { item ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { navigator.push(Screen.Detail(item.id)) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            val icon = categories.firstOrNull { it.id == item.categoryId }?.icon ?: "🔑"
                            Text("$icon ${item.title}", style = MaterialTheme.typography.titleMedium)
                            Text(item.username, style = MaterialTheme.typography.bodyLarge)
                            Hint(item.websiteDomain + if (item.sensitive) " · sensível" else "")
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { navigator.push(Screen.Edit(null)) },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Nova conta") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

// ------------------------------------------------------------- detalhe ---

@Composable
fun CredentialDetailScreen(container: AppContainer, id: String, navigator: Navigator) {
    var credential by remember { mutableStateOf<Credential?>(null) }
    var showPassword by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(id) { credential = container.vault.get(id) }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(credential?.title ?: "Conta", onBack = navigator::pop)
        val c = credential ?: return@Column
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Hint("Site")
                Text(c.websiteDomain, style = MaterialTheme.typography.bodyLarge)
                HorizontalDivider()
                Hint("Usuário")
                Text(c.username, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { copyPlain(context, "Usuário", c.username); message = "Usuário copiado." }) {
                    Text("Copiar usuário")
                }
                HorizontalDivider()
                Hint("Senha")
                Text(if (showPassword) c.password else "••••••••••", style = MonoStyle)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        showPassword = !showPassword
                        if (showPassword) scope.launch { container.vault.recordPasswordViewed(c.id) }
                    }) { Text(if (showPassword) "Ocultar" else "Mostrar senha") }
                    TextButton(onClick = {
                        copySecret(context, "Senha", c.password)
                        message = "Senha copiada. Ela some da área de transferência em 30 segundos."
                    }) { Text("Copiar senha") }
                }
                Hint("Força: ${PasswordStrength.estimate(c.password).label}")
                if (c.sensitive) Hint("Conta sensível: o preenchimento sempre pede confirmação no celular.")
                if (c.notes.isNotBlank()) {
                    HorizontalDivider()
                    Hint("Observações")
                    Text(c.notes)
                }
                Hint("Atualizada em ${formatTime(c.updatedAt)}")
            }
            message?.let { Text(it, color = com.chavesegura.ui.SuccessColor) }
            BigButton("Editar", { navigator.push(Screen.Edit(c.id)) })
            BigOutlinedButton("Excluir conta", { confirmDelete = true })
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir esta conta?") },
            text = { Text("A senha de “${credential?.title}” será apagada do cofre. Isso não pode ser desfeito.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.vault.delete(id)
                        confirmDelete = false
                        navigator.pop()
                    }
                }) { Text("Excluir") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

// --------------------------------------------------------- formulário ---

@Composable
fun CredentialEditScreen(container: AppContainer, id: String?, presetPassword: String?, navigator: Navigator) {
    val categories by remember { container.vault.observeCategories() }.collectAsState(initial = emptyList())
    var loaded by remember { mutableStateOf(id == null) }
    var title by remember { mutableStateOf("") }
    var website by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf(presetPassword ?: "") }
    var notes by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<String?>("outros") }
    var sensitive by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(presetPassword != null) }
    var showGenerator by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(id) {
        if (id != null) {
            container.vault.get(id)?.let { c ->
                title = c.title
                website = c.websiteDomain
                username = c.username
                password = c.password
                notes = c.notes
                categoryId = c.categoryId
                sensitive = c.sensitive
            }
            loaded = true
        }
    }

    fun save(afterSave: () -> Unit = { navigator.pop() }) {
        scope.launch {
            try {
                container.vault.save(id, title, username, password, website, categoryId, notes, sensitive)
                afterSave()
            } catch (e: IllegalArgumentException) {
                error = e.message
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppTopBar(if (id == null) "Nova conta" else "Editar conta", onBack = navigator::pop)
        if (!loaded) return@Column
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(title, { title = it }, label = { Text("Nome do serviço (ex.: Banco Exemplo)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                website, { website = it },
                label = { Text("Endereço do site (ex.: banco.exemplo.com)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("A senha só será preenchida no computador quando o endereço do site for exatamente este.")
            OutlinedTextField(
                username, { username = it },
                label = { Text("Usuário ou e-mail") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                password, { password = it },
                label = { Text("Senha") },
                singleLine = true,
                textStyle = MonoStyle,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton({ showPassword = !showPassword }) { Text(if (showPassword) "Ocultar" else "Mostrar") } },
                modifier = Modifier.fillMaxWidth(),
            )
            if (password.isNotEmpty()) Hint("Força: ${PasswordStrength.estimate(password).label}")
            BigOutlinedButton(if (showGenerator) "Fechar gerador" else "Gerar senha forte", { showGenerator = !showGenerator })
            if (showGenerator) {
                GeneratorPanel(container.generator) { generated ->
                    password = generated
                    showPassword = true
                }
            }

            Text("Categoria", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    FilterChip(
                        selected = categoryId == c.id,
                        onClick = {
                            categoryId = c.id
                            if (c.sensitiveByDefault) sensitive = true
                        },
                        label = { Text("${c.icon} ${c.name}") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Conta sensível", style = MaterialTheme.typography.titleMedium)
                    Hint("Bancos e serviços importantes: sempre pedir confirmação, mesmo no modo automático.")
                }
                Switch(checked = sensitive, onCheckedChange = { sensitive = it })
            }
            OutlinedTextField(notes, { notes = it }, label = { Text("Observações (opcional)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            ErrorText(error)
            BigButton("Salvar", { save() })
            if (id == null) {
                // Gera uma senha forte e já salva a conta em um toque.
                BigOutlinedButton(
                    "Gerar senha forte e salvar",
                    {
                        password = container.generator.generate()
                        showPassword = true
                        save { navigator.pop() }
                    },
                    enabled = title.isNotBlank() && website.isNotBlank(),
                )
            }
        }
    }
}

// ------------------------------------------------------------- gerador ---

@Composable
fun GeneratorPanel(generator: PasswordGenerator, onUse: (String) -> Unit) {
    var options by remember { mutableStateOf(GeneratorOptions()) }
    var generated by remember { mutableStateOf(generator.generate(options)) }
    fun regenerate() {
        generated = runCatching { generator.generate(options) }.getOrDefault("")
    }

    SectionCard {
        Text(generated.ifEmpty { "Escolha ao menos um tipo" }, style = MonoStyle)
        Hint("Tamanho: ${options.length} · cerca de ${PasswordGenerator.entropyBits(options)} bits")
        Slider(
            value = options.length.toFloat(),
            onValueChange = {
                options = options.copy(length = it.toInt())
                regenerate()
            },
            valueRange = 12f..40f,
        )
        OptionRow("Letras minúsculas", options.lowercase) { options = options.copy(lowercase = it); regenerate() }
        OptionRow("Letras maiúsculas", options.uppercase) { options = options.copy(uppercase = it); regenerate() }
        OptionRow("Números", options.digits) { options = options.copy(digits = it); regenerate() }
        OptionRow("Símbolos (!@#…)", options.symbols) { options = options.copy(symbols = it); regenerate() }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(::regenerate) { Text("Gerar outra") }
            TextButton({ if (generated.isNotEmpty()) onUse(generated) }) { Text("Usar esta senha") }
        }
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Gerador avulso: gera e abre o formulário de nova conta já com a senha. */
@Composable
fun GeneratorScreen(container: AppContainer, navigator: Navigator) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Gerador de senhas", onBack = navigator::pop)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("Toque em “Usar esta senha” para criar uma conta nova com ela.")
            GeneratorPanel(container.generator) { password ->
                navigator.pop()
                navigator.push(Screen.Edit(null, presetPassword = password))
            }
            BigOutlinedButton("Copiar uma senha nova", {
                copySecret(context, "Senha", container.generator.generate())
            })
        }
    }
}
