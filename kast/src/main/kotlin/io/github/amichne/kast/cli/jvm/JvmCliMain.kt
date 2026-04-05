package io.github.amichne.kast.cli.jvm

import io.github.amichne.kast.cli.KastCli
import io.github.amichne.kast.standalone.StandaloneRuntime
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val cli = KastCli(
        internalDaemonRunner = { options -> StandaloneRuntime.run(options) },
    )
    exitProcess(cli.run(args))
}
