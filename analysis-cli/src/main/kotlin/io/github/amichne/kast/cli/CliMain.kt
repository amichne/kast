package io.github.amichne.kast.cli

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(KastCli().run(args))
}
