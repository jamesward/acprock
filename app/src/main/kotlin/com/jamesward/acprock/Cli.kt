package com.jamesward.acprock

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking

class Acprock : CliktCommand(name = "acprock") {
    private val port by option("--port", help = "Port to listen on").int().default(9999)
    private val harnessCmd by option("--harness-cmd", help = "Harness binary to spawn for ACP")
        .default("kiro-cli")
    private val harnessArgs by option("--harness-arg", help = "Arg(s) passed to the harness; repeat to supply many")
        .multiple(default = listOf("acp"))

    override fun run() {
        val harness = HarnessSpec(command = harnessCmd, args = harnessArgs)
        val bridge = AcpBridge(harness)
        Runtime.getRuntime().addShutdownHook(Thread { runBlocking { bridge.close() } })

        embeddedServer(CIO, port = port) { acprockModule(bridge) }.start(wait = true)
    }
}

fun main(args: Array<String>) = Acprock().main(args)
