package org.olcbox.app.data.datasource

import kotlinx.coroutines.test.runTest
import org.olcbox.app.data.importer.UnifiedServersJsonParser
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.VkTurnConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One `vpn.servers.full` JSON carries vless + AmneziaWG + WDTT at once and must import as a flat list
 * of locations, each mapped onto the app's existing engine/proxy models.
 */
class UnifiedServersJsonImportTest {

    private class FakeLocationsDataSource(var stored: LocationBundleV4? = null) : LocationsDataSource {
        override suspend fun loadLocationBundle(): LocationBundleV4? = stored

        override suspend fun saveLocationBundle(bundle: LocationBundleV4) {
            stored = bundle
        }

        override suspend fun loadLegacyLocations(): List<Pair<String, String>> = emptyList()

        override suspend fun loadLegacyActiveLocationId(): String? = null
    }

    private val catalogue = """
        {
          "schema": "vpn.servers.full",
          "version": 1,
          "updatedAt": "2026-10-02T06:10:00Z",
          "servers": {
            "vless": [
              {
                "id": 1663,
                "name": "NL Amsterdam 9",
                "protocol": "vless",
                "address": "132.243.235.72",
                "port": 443,
                "uuid": "7f3a2c1e-9b4d-4a6f-8c11-2d5e6f7a8b90",
                "flow": "xtls-rprx-vision",
                "network": "tcp",
                "security": "reality",
                "sni": "yahoo.com",
                "publicKey": "Pq8sT7vK3nR9xW2mB5dY1cA6fH4eL0jQ8uZ3oT6iS2k",
                "shortId": "abcd1234",
                "fingerprint": "chrome",
                "uri": "vless://7f3a2c1e-9b4d-4a6f-8c11-2d5e6f7a8b90@132.243.235.72:443?security=reality&type=tcp#NL"
              }
            ],
            "awg": [
              {
                "id": 270,
                "name": "CZ Praga 4",
                "protocol": "amneziawg",
                "address": "13.143.234.7",
                "port": 56001,
                "fileName": "13.143.234.7_Praga_4.conf",
                "config": "[Interface]\nPrivateKey = 6Jv3mS9t2YbP0qRcW7fXk1aH4nE8uL5dZ3oV6iT0sQk=\nAddress = 10.8.1.5/32\nJc = 4\nJmin = 40\nJmax = 70\nS1 = 86\nS2 = 574\nH1 = 1234567890\nH2 = 2345678901\nH3 = 3456789012\nH4 = 4567890123\n\n[Peer]\nPublicKey = 9pLw2YhQ7rVt3FmZ8xKc5AnB1dE4gU6iS0oJ2bN7tXk=\nEndpoint = 13.143.234.7:56001\nAllowedIPs = 0.0.0.0/0\n"
              }
            ],
            "wdtt": [
              {
                "id": 130,
                "name": "WDTT Amsterdam",
                "protocol": "wdtt",
                "address": "132.243.235.72",
                "dtlsPort": 56000,
                "wgPort": 56001,
                "listenPort": 9000,
                "password": "Tkd3SkJy9bbzuHZJ",
                "hashes": "hash1,hash2",
                "workersPerHash": 9,
                "maxDevices": 5,
                "iphone": "wdtt://132.243.235.72:56000:56001:9000:Tkd3SkJy9bbzuHZJ:hash1,hash2",
                "uri": "qwdtt://config?host=132.243.235.72&dtlsPort=56000&wgPort=56001&listenPort=9000&password=Tkd3SkJy9bbzuHZJ&hashes=hash1,hash2&workersPerHash=9"
              }
            ]
          }
        }
    """.trimIndent()

    @Test
    fun importsAllThreeProtocolsAsAFlatList() = runTest {
        val source = FakeLocationsDataSource()

        LocationsRepositoryImpl(source).importText(catalogue)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(3, imported.locations.size)
        assertEquals(imported.locations.first().storageId, imported.activeLocationId)

        val vless = imported.locations.first { it.location.proxy?.type == ProxyProfile.TYPE_VLESS }.location
        assertEquals(EngineType.Standard, vless.engine)
        assertEquals("NL Amsterdam 9", vless.name)
        assertEquals("132.243.235.72", vless.proxy?.server)
        assertEquals(443, vless.proxy?.serverPort)
        assertEquals("7f3a2c1e-9b4d-4a6f-8c11-2d5e6f7a8b90", vless.proxy?.uuid)
        assertEquals(ProxyProfile.SECURITY_REALITY, vless.proxy?.security)
        assertEquals("xtls-rprx-vision", vless.proxy?.flow)
        assertEquals("Pq8sT7vK3nR9xW2mB5dY1cA6fH4eL0jQ8uZ3oT6iS2k", vless.proxy?.realityPublicKey)
        assertEquals("abcd1234", vless.proxy?.realityShortId)

        val awg = imported.locations.first { it.location.proxy?.type == ProxyProfile.TYPE_AMNEZIAWG }.location
        assertEquals(EngineType.Standard, awg.engine)
        assertEquals("CZ Praga 4", awg.name)
        assertEquals("13.143.234.7", awg.proxy?.server)
        assertEquals(56001, awg.proxy?.serverPort)
        assertTrue(awg.proxy?.awgConfig?.contains("Jc = 4") == true, awg.proxy?.awgConfig.toString())
        assertEquals(true, awg.proxy?.isComplete())

        val wdtt = imported.locations.first { it.location.engine == EngineType.VkTurn }.location
        val vk = wdtt.vkturn
        assertNotNull(vk)
        assertEquals(VkTurnConfig.CORE_WDTT, vk.core)
        assertEquals("132.243.235.72", vk.wdttPeer)
        assertEquals(56000, vk.wdttPort)
        assertEquals("Tkd3SkJy9bbzuHZJ", vk.wdttPassword)
        assertEquals(9, vk.wdttWorkers)
        assertEquals(9000, vk.listenPort)
        assertEquals("hash1\nhash2", vk.vkLink)
        assertEquals(true, vk.isStorable())
    }

    @Test
    fun fallsBackToUriWhenVlessFieldsAreMissing() = runTest {
        val source = FakeLocationsDataSource()
        val json = """
            {"schema":"vpn.servers.full","servers":{"vless":[
              {"name":"Fallback","protocol":"vless",
               "uri":"vless://11111111-2222-3333-4444-555555555555@example.com:443?security=tls&type=tcp#Fallback"}
            ]}}
        """.trimIndent()

        LocationsRepositoryImpl(source).importText(json)

        val imported = source.stored
        assertNotNull(imported)
        assertEquals(1, imported.locations.size)
        val profile = imported.locations.first().location.proxy
        assertEquals("example.com", profile?.server)
        assertEquals(443, profile?.serverPort)
        assertEquals("11111111-2222-3333-4444-555555555555", profile?.uuid)
    }

    @Test
    fun ignoresJsonWithoutTheServersContainer() {
        assertNull(UnifiedServersJsonParser.parse("""{"links":["vless://x@y:443"]}"""))
        assertNull(UnifiedServersJsonParser.parse("""{"outbounds":[]}"""))
    }
}
