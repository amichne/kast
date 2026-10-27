package io.github.amichne.kast.cli.mcp

import io.github.amichne.kast.cli.direct.KastDirectToolSession
import io.github.amichne.kast.cli.direct.selectDirectCatalog
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.cli.rpc.InvocationRecordKind
import io.github.amichne.kast.cli.rpc.OneShotInvocationRecord
import java.io.BufferedInputStream
import java.nio.file.Path

/** The MCP client owns this stdio process. No app server or broker is started by this transport. */
object KastMcpMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val selected = when (val selection = selectDirectCatalog(args.toList())) {
            is Refinement.Refined -> selection.value
            is Refinement.Rejected -> return
        }
        if (selected.command.isNotEmpty()) return
        val directory = Path.of("").toAbsolutePath()
        val home = Path.of(System.getProperty("user.home"))
        val tools = KastDirectToolSession.installed(directory, home, System.getenv(), selected.catalog) ?: return
        val record = OneShotInvocationRecord.begin(InvocationRecordKind.MCP_SESSION) ?: return
        record.use {
            KastMcpServer(
                    catalog = tools.catalog,
                    invoke = tools::invoke,
                    root = tools.root,
                    onInitialize = tools.start,
                )
                .run(BufferedInputStream(System.`in`), System.out)
        }
    }
}
