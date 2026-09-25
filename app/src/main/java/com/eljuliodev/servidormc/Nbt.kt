package com.eljuliodev.servidormc

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * Lector mínimo de NBT (el formato binario de Minecraft): alcanza para buscar un `TAG_Long`
 * por nombre dentro de un compuesto. Salta el resto de etiquetas sin interpretarlas.
 */
object Nbt {

    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    /** Primer `TAG_Long` llamado [name] en el árbol (recursivo), o null si no está. */
    fun findLong(input: InputStream, name: String): Long? = runCatching {
        val data = DataInputStream(BufferedInputStream(input))
        if (data.readUnsignedByte() != TAG_COMPOUND) return null
        readName(data) // nombre del compuesto raíz
        find(data, name)
    }.getOrNull()

    private fun find(data: DataInputStream, wanted: String): Long? {
        while (true) {
            val type = data.readUnsignedByte()
            if (type == TAG_END) return null
            val name = readName(data)
            when {
                type == TAG_LONG && name == wanted -> return data.readLong()
                type == TAG_COMPOUND -> find(data, wanted)?.let { return it }
                else -> skip(data, type)
            }
        }
    }

    private fun skipCompound(data: DataInputStream) {
        while (true) {
            val type = data.readUnsignedByte()
            if (type == TAG_END) return
            skipFully(data, data.readUnsignedShort())
            skip(data, type)
        }
    }

    private fun skip(data: DataInputStream, type: Int) {
        when (type) {
            TAG_BYTE -> skipFully(data, 1)
            TAG_SHORT -> skipFully(data, 2)
            TAG_INT, TAG_FLOAT -> skipFully(data, 4)
            TAG_LONG, TAG_DOUBLE -> skipFully(data, 8)
            TAG_BYTE_ARRAY -> skipFully(data, data.readInt())
            TAG_STRING -> skipFully(data, data.readUnsignedShort())
            TAG_LIST -> {
                val elementType = data.readUnsignedByte()
                val length = data.readInt()
                repeat(length) { skip(data, elementType) }
            }
            TAG_COMPOUND -> skipCompound(data)
            TAG_INT_ARRAY -> skipFully(data, data.readInt() * 4)
            TAG_LONG_ARRAY -> skipFully(data, data.readInt() * 8)
            else -> error("TAG de NBT desconocido: $type")
        }
    }

    private fun readName(data: DataInputStream): String {
        val bytes = ByteArray(data.readUnsignedShort())
        data.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipFully(data: DataInputStream, count: Int) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = data.read(buffer, 0, minOf(buffer.size, remaining))
            if (read < 0) error("NBT truncado")
            remaining -= read
        }
    }
}
