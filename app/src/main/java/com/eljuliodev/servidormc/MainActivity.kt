package com.eljuliodev.servidormc

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

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // La web navega primero; si ya está en la lista, cerramos la Activity.
                webUi.handleBack { handled -> if (!handled) finish() }
            }
        })
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
}
