package io.github.amichne.kast.headless

import com.intellij.openapi.application.ApplicationStarter

fun main(args: Array<String>) {
    HeadlessRuntime.configureSystemProperties(HeadlessBootstrapOptions.parse(args))
    val main = Class.forName("com.intellij.idea.Main").getMethod("main", Array<String>::class.java)
    main.invoke(null, HeadlessRuntime.ideaMainArgs(args))
}

class HeadlessApplicationStarter(
    private val runRuntime: (HeadlessServerOptions) -> Unit = HeadlessRuntime::run,
) : ApplicationStarter {
    override val isHeadless: Boolean = true
    override val requiredModality: Int = ApplicationStarter.NOT_IN_EDT

    override fun main(args: List<String>) {
        runRuntime(HeadlessServerOptions.parseStarterArgs(args))
    }

    companion object {
        const val COMMAND_NAME = "kast-headless"
    }
}
