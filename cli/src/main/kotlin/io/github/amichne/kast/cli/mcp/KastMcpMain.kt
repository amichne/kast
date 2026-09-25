package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.direct.KastDirectToolSession
import java.io.BufferedInputStream
import java.nio.file.Path

/** The MCP client owns this stdio process. No app server or broker is started by this transport. */
object KastMcpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isNotEmpty()) return
        val directory = Path.of("").toAbsolutePath()
        val home = Path.of(System.getProperty("user.home"))
        val tools = KastDirectToolSession.installed(directory, home, System.getenv()) ?: return
        KastMcpServer(
                catalog = tools.catalog,
                invoke = tools.invokeCanonical,
                root = tools.root,
                supplemental = tools.supplemental,
                onInitialize = tools.start,
            )
            .run(BufferedInputStream(System.`in`), System.out)
    }
}
