package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.cli.KastCli
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(KastCli().run(args))
}
