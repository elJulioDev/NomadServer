package com.eljuliodev.servidormc

import android.app.Application

class NomadApplication : Application() {
    val serverManager: ServerManager by lazy { ServerManager(this) }
}
