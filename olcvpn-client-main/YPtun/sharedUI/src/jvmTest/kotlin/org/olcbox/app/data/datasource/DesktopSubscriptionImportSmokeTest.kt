package org.olcbox.app.data.datasource

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The desktop subscription import end-to-end: a real http(s) URL is fetched with the JVM HTTP client,
 * parsed and persisted through [JvmLocationsDataSourceImpl]. Guards against a regression where the
 * Windows app silently fails to "add subscription" while Android still works.
 */
class DesktopSubscriptionImportSmokeTest {

    @Test
    fun importsSubscriptionFromHttpUrl() = runBlocking(Dispatchers.IO) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { exchange ->
            val body = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443" +
                "?encryption=none&security=tls&type=tcp#Desktop%20Test\n"
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val dir = Files.createTempDirectory("yourvpn-sub-import")
            val repo = LocationsRepositoryImpl(JvmLocationsDataSourceImpl(dir))

            val rawVless = "vless://11111111-2222-3333-4444-555555555555@203.0.113.10:443" +
                "?encryption=none&security=tls&type=tcp#Desktop%20Test"
            assertTrue(repo.importText(rawVless, null), "raw vless import failed (parser problem)")

            val ok = repo.importText("http://127.0.0.1:$port/sub", null)

            val bundle = repo.getBundle()
            assertTrue(ok, "http subscription fetch failed (fetch problem), not the parser")
            assertTrue(bundle.locations.isNotEmpty(), "no locations persisted; import pipeline broken")
            assertTrue(
                bundle.locations.any { it.location.proxy?.server == "203.0.113.10" },
                "the fetched subscription was not parsed into a location"
            )
        } finally {
            server.stop(0)
        }
    }
}
