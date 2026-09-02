package pl.lambada.songsync.util.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpMethod
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object Ktor {
    val client = HttpClient(CIO.create {
        // Here goes all the Engine config
        // TODO: Add proxy support
        // proxy = ProxyConfig(
        //     type = Proxy.Type.SOCKS,
        //     sa = java.net.InetSocketAddress(3030)
        // )
    }) {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 20_000
            socketTimeoutMillis = 20_000
        }
        install(HttpRequestRetry) {
            maxRetries = 1
            retryIf { request, response ->
                request.method == HttpMethod.Get && response.status.value in 500..599
            }
            retryOnExceptionIf(maxRetries = 1) { request, _ ->
                request.method == HttpMethod.Get
            }
            exponentialDelay()
        }
        // In case of adding plugins, add them here
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}
