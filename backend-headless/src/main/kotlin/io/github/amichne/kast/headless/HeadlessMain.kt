package io.github.amichne.kast.headless

import com.intellij.openapi.application.ModernApplicationStarter
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    HeadlessRuntime.configureSystemProperties(HeadlessBootstrapOptions.parse(args))
    val main = Class.forName("com.intellij.idea.Main").getMethod("main", Array<String>::class.java)
    main.invoke(null, HeadlessRuntime.ideaMainArgs(args))
}

class HeadlessApplicationStarter : ModernApplicationStarter() {
    override suspend fun start(args: List<String>) {
        runCatching {
            HeadlessRuntime.run(HeadlessServerOptions.parseStarterArgs(args))
        }.onFailure { error ->
            error.printStackTrace(System.err)
            exitProcess(1)
        }
    }

    companion object {
        const val COMMAND_NAME = "kast-headless"
    }
}
