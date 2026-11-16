package com.chavesegura

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.chavesegura.ui.ChaveSeguraApp

/**
 * FragmentActivity por causa do BiometricPrompt. FLAG_SECURE impede capturas
 * de tela e a miniatura nos apps recentes (threat-model C7).
 */
class MainActivity : FragmentActivity() {
    private val container get() = (application as ChaveSeguraApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent { ChaveSeguraApp(container) }
    }

    override fun onStart() {
        super.onStart()
        container.coordinator.start()
    }

    override fun onStop() {
        super.onStop()
        container.coordinator.stop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        container.session.touch()
    }

    /** Mantém a tela ligada enquanto o cofre está aberto (para atender pedidos do computador). */
    fun keepScreenOn(enabled: Boolean) {
        if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
