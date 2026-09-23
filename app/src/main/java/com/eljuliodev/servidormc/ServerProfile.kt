package com.eljuliodev.servidormc

/** Un servidor creado por el usuario; una instancia de Minecraft con su propio directorio. */
data class ServerProfile(
    val id: String,
    val name: String,
    val ramMb: Int,
)