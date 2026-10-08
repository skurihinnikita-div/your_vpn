package org.olcbox.app.data.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.olcbox.app.data.model.EngineType
import org.olcbox.app.data.model.LocationConfig
import org.olcbox.app.data.model.ProxyProfile
import org.olcbox.app.data.model.VkTurnConfig

/**
 * Parses the unified server catalogue the project distributes as `Пример.json`:
 *
 * ```json
 * {
 *   "schema": "vpn.servers.full",
 *   "version": 1,
 *   "servers": {
 *     "vless": [ { "id": 1663, "name": "🇳🇱 Амстердам 9", "address": "…", "port": 443,
 *                  "uuid": "…", "network": "tcp", "security": "reality", "sni": "…",
 *                  "publicKey": "…", "shortId": "…", "fingerprint": "chrome", "uri": "vless://…" } ],
 *     "awg":   [ { "name": "…", "address": "…", "port": 56001, "config": "[Interface]\n…" } ],
 *     "wdtt":  [ { "name": "…", "address": "…", "dtlsPort": 56000, "wgPort": 56001,
 *                  "listenPort": 9000, "password": "…", "hashes": "a,b", "workersPerHash": 9,
 *                  "iphone": "wdtt://…", "uri": "qwdtt://config?…" } ]
 *   }
 * }
 * ```
 *
 * One file carries every protocol at once. The three families map onto the app's existing models —
 * vless/awg onto [EngineType.Standard] + [ProxyProfile] (awg keeps its INI in [ProxyProfile.awgConfig]),
 * wdtt onto [EngineType.VkTurn] + [VkTurnConfig] with [VkTurnConfig.CORE_WDTT]. No new protocol is
 * introduced.
 *
 * Parsing prefers the discrete fields; the embedded `uri`/`iphone` strings are only consulted as a
 * fallback when the fields are missing/incomplete (`uri` for vless, `uri`/`iphone` for wdtt).
 */
object UnifiedServersJsonParser {

    const val SCHEMA = "vpn.servers.full"

    /** Parse the catalogue, or null when [text] is not this format (or carries no usable server). */
    fun parse(text: String): List<LocationConfig>? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{")) return null
        val root = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
        val servers = root.obj("servers") ?: return null
        if (listOf("vless", "awg", "wdtt").none { servers.arr(it) != null }) return null

        val result = mutableListOf<LocationConfig>()
        servers.arr("vless")?.forEach { element ->
            (element as? JsonObject)?.let { parseVless(it)?.let(result::add) }
        }
        servers.arr("awg")?.forEach { element ->
            (element as? JsonObject)?.let { parseAwg(it)?.let(result::add) }
        }
        servers.arr("wdtt")?.forEach { element ->
            (element as? JsonObject)?.let { parseWdtt(it)?.let(result::add) }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseVless(el: JsonObject): LocationConfig? {
        val name = el.str("name")
        val uri = el.str("uri")

        // Discrete fields first; the embedded vless:// link is the fallback.
        val server = el.str("address").ifBlank { el.str("server") }
        val port = el.int("port") ?: el.int("server_port") ?: 0
        val uuid = el.str("uuid")
        val fromFields = if (server.isNotBlank() && port in 1..65535 && uuid.isNotBlank()) {
            ProxyProfile(
                tag = name,
                type = ProxyProfile.TYPE_VLESS,
                server = server,
                serverPort = port,
                uuid = uuid,
                flow = el.str("flow"),
                network = networkOf(el.str("network")),
                security = securityOf(el.str("security")),
                sni = el.str("sni"),
                alpn = alpnOf(el),
                fingerprint = el.str("fingerprint"),
                allowInsecure = el.bool("allowInsecure") ?: el.bool("allow_insecure") ?: false,
                realityPublicKey = el.str("publicKey").ifBlank { el.str("public_key") },
                realityShortId = el.str("shortId").ifBlank { el.str("short_id") },
                path = el.str("path"),
                host = el.str("host"),
            )
        } else {
            null
        }

        val profile = fromFields?.takeIf { it.isComplete() }
            ?: uri.takeIf { it.isNotBlank() }?.let { VlessUriParser.parse(it) }?.takeIf { it.isComplete() }
            ?: fromFields
            ?: return null
        if (!profile.isComplete()) return null

        val display = name.ifBlank { profile.tag }.ifBlank { profile.displayName() }
        return LocationConfig(
            name = display,
            engine = EngineType.Standard,
            proxy = profile.copy(tag = display),
        ).normalized()
    }

    private fun parseAwg(el: JsonObject): LocationConfig? {
        val name = el.str("name")
        val config = el.str("config")
        if (config.isBlank()) return null

        var profile = AmneziaWgParser.parse(config) ?: return null
        val server = el.str("address").ifBlank { el.str("server") }
        if (server.isNotBlank()) profile = profile.copy(server = server)
        val port = el.int("port") ?: el.int("server_port") ?: 0
        if (port in 1..65535) profile = profile.copy(serverPort = port)

        val display = name.ifBlank { profile.tag }.ifBlank { "AmneziaWG" }
        return LocationConfig(
            name = display,
            engine = EngineType.Standard,
            proxy = profile.copy(tag = display),
        ).normalized()
    }

    private fun parseWdtt(el: JsonObject): LocationConfig? {
        val name = el.str("name")
        var peer = el.str("address").ifBlank { el.str("server") }
        var dtlsPort = el.int("dtlsPort") ?: el.int("dtls_port") ?: 0
        var password = el.str("password").ifBlank { el.str("pass") }
        var hashes = el.str("hashes")
        var workers = el.int("workersPerHash") ?: el.int("workers_per_hash") ?: el.int("workers") ?: 0
        var listenPort = el.int("listenPort") ?: el.int("listen_port") ?: 0

        if (peer.isBlank() || password.isBlank()) {
            parseWdttFallback(el.str("uri"), el.str("iphone"))?.let { fb ->
                if (peer.isBlank()) peer = fb.peer
                if (dtlsPort !in 1..65535) dtlsPort = fb.dtlsPort
                if (password.isBlank()) password = fb.password
                if (hashes.isBlank()) hashes = fb.hashes
                if (workers <= 0) workers = fb.workers
                if (listenPort !in 1..65535) listenPort = fb.listenPort
            }
        }
        if (peer.isBlank() || password.isBlank()) return null

        val display = name.ifBlank { "WDTT $peer" }
        return LocationConfig(
            name = display,
            engine = EngineType.VkTurn,
            vkturn = VkTurnConfig(
                core = VkTurnConfig.CORE_WDTT,
                wdttPeer = peer,
                wdttPort = dtlsPort.takeIf { it in 1..65535 } ?: 0,
                wdttPassword = password,
                wdttWorkers = workers,
                vkLink = splitHashes(hashes).joinToString("\n"),
                listenPort = listenPort.takeIf { it in 1..65535 } ?: LocationConfig.DEFAULT_FREETURN_PORT,
            ),
        ).normalized()
    }

    private data class WdttFallback(
        val peer: String,
        val dtlsPort: Int,
        val password: String,
        val hashes: String,
        val workers: Int,
        val listenPort: Int,
    )

    /**
     * Fallback for a wdtt entry whose discrete fields are missing/incomplete: reads the `uri`
     * (qwdtt://config?host=…&dtlsPort=…&password=…&hashes=…&listenPort=…&workersPerHash=…) or the
     * iOS-style `iphone` (wdtt://host:dtlsPort:wgPort:listenPort:password:hashes).
     */
    private fun parseWdttFallback(uri: String, iphone: String): WdttFallback? {
        if (uri.startsWith("qwdtt://", ignoreCase = true)) {
            val query = UriCodec.parseQuery(uri.substringAfter('?', ""))
            val peer = (query["host"] ?: query["peer"]).orEmpty().trim()
            val pass = (query["password"] ?: query["pass"]).orEmpty().trim()
            if (peer.isNotBlank() && pass.isNotBlank()) {
                return WdttFallback(
                    peer = peer,
                    dtlsPort = (query["dtlsPort"] ?: query["dtls_port"])?.toIntOrNull() ?: 0,
                    password = pass,
                    hashes = query["hashes"].orEmpty(),
                    workers = (query["workersPerHash"] ?: query["workers"])?.toIntOrNull() ?: 0,
                    listenPort = (query["listenPort"] ?: query["port"])?.toIntOrNull() ?: 0,
                )
            }
        }
        if (iphone.startsWith("wdtt://", ignoreCase = true)) {
            val parts = iphone.substring("wdtt://".length).split(':')
            // host:dtlsPort:wgPort:listenPort:password:hash[,hash…]
            if (parts.size >= 6) {
                val host = parts[0].trim()
                val pass = parts[4].trim()
                if (host.isNotBlank() && pass.isNotBlank()) {
                    return WdttFallback(
                        peer = host,
                        dtlsPort = parts[1].trim().toIntOrNull() ?: 0,
                        password = pass,
                        hashes = parts.subList(5, parts.size).joinToString(":"),
                        workers = 0,
                        listenPort = parts[3].trim().toIntOrNull() ?: 0,
                    )
                }
            }
        }
        return null
    }

    private fun networkOf(value: String): String = when (value.trim().lowercase()) {
        "ws", "websocket" -> ProxyProfile.NETWORK_WS
        "grpc" -> ProxyProfile.NETWORK_GRPC
        "http", "h2" -> ProxyProfile.NETWORK_HTTP
        "httpupgrade" -> ProxyProfile.NETWORK_HTTPUPGRADE
        "xhttp", "splithttp" -> ProxyProfile.NETWORK_XHTTP
        else -> ProxyProfile.NETWORK_TCP
    }

    private fun securityOf(value: String): String = when (value.trim().lowercase()) {
        "tls" -> ProxyProfile.SECURITY_TLS
        "reality" -> ProxyProfile.SECURITY_REALITY
        else -> ProxyProfile.SECURITY_NONE
    }

    private fun alpnOf(el: JsonObject): List<String> {
        el.arr("alpn")?.let { arr ->
            val list = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
                .filter { it.isNotEmpty() }
            if (list.isNotEmpty()) return list
        }
        return el.str("alpn").split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Splits a hash list the way the qWDTT core does: commas, semicolons or whitespace. */
    private fun splitHashes(raw: String): List<String> =
        raw.split(',', ';', '\n', '\r', '\t', ' ').map { it.trim() }.filter { it.isNotEmpty() }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
}
