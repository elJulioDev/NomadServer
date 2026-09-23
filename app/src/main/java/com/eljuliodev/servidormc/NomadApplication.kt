package com.eljuliodev.servidormc

import android.app.Application

class NomadApplication : Application() {

    private val managers = mutableMapOf<String, ServerManager>()

    /** Un ServerManager por perfil, creado la primera vez y reutilizado después. */
    fun managerFor(serverId: String): ServerManager =
        managers.getOrPut(serverId) { ServerManager(this, serverId) }
}