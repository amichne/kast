package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.distribution.output.cliOutputRejection
import io.github.amichne.kast.distribution.output.renderCliOutput
import io.github.amichne.kast.kernel.Refinement
import java.io.OutputStream
import sun.misc.Signal

internal fun runPublicCliInvocation(
    prepare: () -> PublicPreparedOutput,
    output: OutputStream,
    format: CliOutputFormat = CliOutputFormat.JSON,
): PublicShellExit {
    val owner = PublicInvocationOwner()
    val worker = Thread({ runPublicWorker(owner, prepare, output, format) }, "kast-public-query")
    for ((name, status) in listOf("INT" to PublicShellExit.INTERRUPTED, "TERM" to PublicShellExit.TERMINATED)) {
        // This process entry retains its handlers through exit, including the interval after worker settlement.
        Signal.handle(Signal(name)) { if (owner.cancel(status)) worker.interrupt() }
    }
    worker.start()
    joinPublicWorker(owner, worker)
    return owner.exit
}

private fun runPublicWorker(
    owner: PublicInvocationOwner,
    prepare: () -> PublicPreparedOutput,
    output: OutputStream,
    format: CliOutputFormat,
) {
    try {
        if (owner.active) owner.publish(presentPublicOutput(prepare(), format), output)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        owner.publishDefect({ presentPublicOutput(publicFailure(ToolRpcFailure.INVOCATION_FAILED), format) }, output)
    } catch (_: Exception) {
        owner.publishDefect({ presentPublicOutput(publicFailure(ToolRpcFailure.INVOCATION_FAILED), format) }, output)
    } finally {
        owner.failedIfActive()
    }
}

internal fun presentPublicOutput(prepared: PublicPreparedOutput, format: CliOutputFormat): PublicPreparedOutput =
    when (prepared.content) {
        PublicOutputContent.HELP -> prepared
        PublicOutputContent.DOCUMENT ->
            when (val rendered = renderCliOutput(prepared.text, format)) {
                is Refinement.Refined -> prepared.copy(text = rendered.value)
                is Refinement.Rejected ->
                    PublicPreparedOutput(cliOutputRejection(rendered.failure, format), PublicShellExit.PROTOCOL)
            }
    }

private fun joinPublicWorker(owner: PublicInvocationOwner, worker: Thread) {
    var interrupted = false
    var joined = false
    while (!joined) {
        try {
            worker.join()
            joined = true
        } catch (_: InterruptedException) {
            interrupted = true
            if (owner.cancel(PublicShellExit.INTERRUPTED)) worker.interrupt()
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}
