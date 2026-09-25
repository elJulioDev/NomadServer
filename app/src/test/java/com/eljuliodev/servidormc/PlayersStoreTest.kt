package com.eljuliodev.servidormc

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PlayersStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `reads names from the json files`() {
        val dir = temp.newFolder()
        File(dir, "ops.json").writeText("""[{"uuid":"x","name":"Steve","level":4}]""")
        File(dir, "whitelist.json").writeText("""[{"uuid":"x","name":"Alex"}]""")
        File(dir, "banned-ips.json").writeText("""[{"ip":"203.0.113.7"}]""")
        File(dir, "banned-players.json").writeText("""[{"uuid":"y","name":"Griefer"}]""")

        assertEquals(listOf("Steve"), PlayersStore.ops(dir))
        assertEquals(listOf("Alex"), PlayersStore.whitelist(dir))
        assertEquals(listOf("203.0.113.7"), PlayersStore.bannedIps(dir))
        assertEquals(listOf("Griefer"), PlayersStore.bannedPlayers(dir))
    }

    @Test
    fun `missing or corrupt files are treated as empty`() {
        val dir = temp.newFolder()
        assertEquals(emptyList<String>(), PlayersStore.ops(dir))
        File(dir, "whitelist.json").writeText("not json")
        assertEquals(emptyList<String>(), PlayersStore.whitelist(dir))
    }
}
