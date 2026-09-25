package com.eljuliodev.servidormc

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * La UI ya no es Compose: es una web (Vite + React + TypeScript) empaquetada en
 * `assets/ui` y construida desde `web/`. Ver [WebUi] para el puente con Kotlin.
 */
class MainActivity : ComponentActivity() {

    private var ui: WebUi? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webUi = WebUi(this, application as NomadApplication)
        ui = webUi
        setContentView(webUi.view)
        webUi.openFromIntent(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // La web navega primero; si ya está en la lista, cerramos la Activity.
                webUi.handleBack { handled -> if (!handled) finish() }
            }
        })
    }

    /** La notificación trae en el intent el id del servidor a abrir. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ui?.openFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ui?.onResume()
    }

    override fun onPause() {
        ui?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        ui?.dispose()
        ui = null
        super.onDestroy()
    }

    companion object {
        /** Extra del intent con el id del servidor a abrir (lo pone la notificación). */
        const val EXTRA_SERVER_ID = "server_id"
    }
}
