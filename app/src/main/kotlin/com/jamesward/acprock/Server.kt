package com.jamesward.acprock

import com.jamesward.acprock.mantle.mantleRoutes
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.jamesward.acprock.Server")

fun Application.acprockModule(bridge: AcpBridge) {
    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            explicitNulls = false
        })
    }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled error", cause)
            call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
                put("error", buildJsonObject {
                    put("message", cause.message ?: cause::class.simpleName.orEmpty())
                    put("type", "internal_server_error")
                })
            })
        }
    }
    routing {
        mantleRoutes(bridge)
    }
}
