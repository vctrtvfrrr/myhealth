package br.etc.victor.myhealthbridge.api

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    try {
        migrate(DatabaseConfig.fromEnvironment())
    } catch (failure: Exception) {
        LoggerFactory.getLogger("br.etc.victor.myhealthbridge.api.Startup")
            .error("Startup aborted before serving any request", failure)
        exitProcess(1)
    }

    embeddedServer(Netty, port = System.getenv("PORT")?.toInt() ?: 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    routing {
        get("/health") {
            call.respondText("OK")
        }
    }
}
