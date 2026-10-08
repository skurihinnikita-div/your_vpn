package org.olcbox.app.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path

/**
 * Talks to the privileged `your_vpn_service` over its loopback control channel. The service (SYSTEM)
 * owns the WinTun adapter, routes, DNS and the engine, so the GUI never needs administrator rights.
 *
 * The channel is authenticated by a per-install secret that the installer writes to
 * `%ProgramData%\your_vpn\service.token`, readable only by the installing user and administrators.
 */
object DesktopServiceClient {

    private const val HOST = "127.0.0.1"
    private const val PORT = 47641
    private const val TIMEOUT_MS = 4_000

    private val json = Json { ignoreUnknownKeys = true }

    /** True when the service is installed, running and speaking our protocol. */
    fun isAvailable(): Boolean = request("hello")?.get("ok")?.jsonPrimitive?.booleanOrNull == true

    /** Hands the sing-box config to the service so it runs the tunnel as SYSTEM. */
    fun startEngine(config: String): Boolean =
        request("startEngine", config)?.get("ok")?.jsonPrimitive?.booleanOrNull == true

    fun stopEngine(): Boolean =
        request("stopEngine")?.get("ok")?.jsonPrimitive?.booleanOrNull == true

    private fun token(): String? = runCatching {
        val base = System.getenv("ProgramData") ?: return null
        Files.readString(Path.of(base, "your_vpn", "service.token")).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun request(cmd: String, config: String? = null): JsonObject? {
        val secret = token() ?: return null
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(HOST, PORT), TIMEOUT_MS)
                socket.soTimeout = TIMEOUT_MS
                val payload = buildJsonObject {
                    put("cmd", cmd)
                    put("token", secret)
                    if (config != null) put("config", config)
                }.toString()
                OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8).use { w ->
                    w.write(payload)
                    w.write("\n")
                    w.flush()
                }
                BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8)).readLine()
                    ?.let { json.parseToJsonElement(it) as? JsonObject }
            }
        }.getOrNull()
    }
}
