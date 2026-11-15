@file:OptIn(ExperimentalMaterial3Api::class)

package com.chavesegura.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chavesegura.AppContainer
import com.chavesegura.MainActivity
import com.chavesegura.ui.screens.ApprovalDialog
import com.chavesegura.ui.screens.CredentialDetailScreen
import com.chavesegura.ui.screens.CredentialEditScreen
import com.chavesegura.ui.screens.DevicesScreen
import com.chavesegura.ui.screens.EventsScreen
import com.chavesegura.ui.screens.GeneratorScreen
import com.chavesegura.ui.screens.PairScreen
import com.chavesegura.ui.screens.SecurityScreen
import com.chavesegura.ui.screens.SettingsScreen
import com.chavesegura.ui.screens.SetupScreen
import com.chavesegura.ui.screens.UnlockScreen
import com.chavesegura.ui.screens.VaultScreen

/** Telas empilhadas sobre as abas. */
sealed interface Screen {
    data class Detail(val id: String) : Screen
    data class Edit(val id: String?, val presetPassword: String? = null) : Screen
    data object Generator : Screen
    data object Pair : Screen
    data object Events : Screen
}

enum class Tab(val label: String, val emoji: String) {
    VAULT("Cofre", "🔐"),
    DEVICES("Computadores", "💻"),
    SECURITY("Segurança", "🛡️"),
    SETTINGS("Ajustes", "⚙️"),
}

class Navigator {
    val stack = mutableStateListOf<Screen>()
    var tab by mutableStateOf(Tab.VAULT)

    fun push(screen: Screen) = stack.add(screen)

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    fun reset() {
        stack.clear()
        tab = Tab.VAULT
    }
}

@Composable
fun ChaveSeguraApp(container: AppContainer) {
    ChaveSeguraTheme {
        val activity = LocalContext.current as MainActivity
        val unlocked by container.session.unlocked.collectAsStateWithLifecycle()
        var created by remember { mutableStateOf<Boolean?>(null) }
        val snackbar = remember { SnackbarHostState() }
        val navigator = remember { Navigator() }

        LaunchedEffect(Unit) { created = container.vault.isCreated() }
        LaunchedEffect(unlocked) {
            activity.keepScreenOn(unlocked)
            if (!unlocked) navigator.reset()
        }
        LaunchedEffect(Unit) {
            container.coordinator.notices.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
        }

        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                when {
                    created == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    created == false -> SetupScreen(container) { created = true }
                    !unlocked -> UnlockScreen(container)
                    else -> MainScaffold(container, navigator, snackbar)
                }
                if (created != true || !unlocked) {
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
        }

        if (unlocked) {
            val approvals by container.coordinator.approvals.collectAsStateWithLifecycle()
            approvals.firstOrNull()?.let { request ->
                ApprovalDialog(request) { credentialId -> container.coordinator.answer(request.requestId, credentialId) }
            }
        }
    }
}

@Composable
private fun MainScaffold(container: AppContainer, navigator: Navigator, snackbar: SnackbarHostState) {
    BackHandler(enabled = navigator.stack.isNotEmpty()) { navigator.pop() }
    val top = navigator.stack.lastOrNull()
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (top == null) {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = navigator.tab == tab,
                            onClick = { navigator.tab = tab },
                            icon = { Text(tab.emoji) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (top) {
                is Screen.Detail -> CredentialDetailScreen(container, top.id, navigator)
                is Screen.Edit -> CredentialEditScreen(container, top.id, top.presetPassword, navigator)
                Screen.Generator -> GeneratorScreen(container, navigator)
                Screen.Pair -> PairScreen(container, navigator)
                Screen.Events -> EventsScreen(container, navigator)
                null -> when (navigator.tab) {
                    Tab.VAULT -> VaultScreen(container, navigator)
                    Tab.DEVICES -> DevicesScreen(container, navigator)
                    Tab.SECURITY -> SecurityScreen(container, navigator)
                    Tab.SETTINGS -> SettingsScreen(container)
                }
            }
        }
    }
}
