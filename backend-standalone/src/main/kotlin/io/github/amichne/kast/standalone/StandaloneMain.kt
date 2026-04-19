package io.github.amichne.kast.standalone

import io.github.amichne.kast.api.client.StandaloneServerOptions

fun main(args: Array<String>) {
    StandaloneRuntime.run(StandaloneServerOptions.parse(args))
}
