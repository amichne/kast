package io.github.amichne.kast.indexer

import java.nio.file.Path

internal const val KAST_INDEXER_COMMAND_NAME = "kast-indexer"

fun main(args: Array<String>) {
    KastIndexerBootstrap.configureSystemProperties(args)
    val ideaMain = Class.forName("com.intellij.idea.Main")
        .getMethod("main", Array<String>::class.java)
    ideaMain.invoke(null, KastIndexerBootstrap.ideaMainArgs(args))
}

internal object KastIndexerBootstrap {
    private const val IDEA_HOME_PREFIX = "--idea-home="

    fun configureSystemProperties(args: Array<String>) {
        System.setProperty("java.awt.headless", "true")
        System.setProperty("idea.is.internal", "true")
        args.ideaHome()?.let { ideaHome ->
            System.setProperty("idea.home.path", ideaHome.toString())
        }
    }

    fun ideaMainArgs(args: Array<String>): Array<String> =
        arrayOf(
            KAST_INDEXER_COMMAND_NAME,
            *args.filterNot { it.startsWith(IDEA_HOME_PREFIX) }.toTypedArray(),
        )

    private fun Array<String>.ideaHome(): Path? =
        firstOrNull { it.startsWith(IDEA_HOME_PREFIX) }
            ?.removePrefix(IDEA_HOME_PREFIX)
            ?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).toAbsolutePath().normalize() }
}
