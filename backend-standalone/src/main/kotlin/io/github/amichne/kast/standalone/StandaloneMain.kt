package io.github.amichne.kast.standalone

fun main(args: Array<String>) {
    StandaloneRuntime.run(StandaloneServerOptions.parse(args))
}
