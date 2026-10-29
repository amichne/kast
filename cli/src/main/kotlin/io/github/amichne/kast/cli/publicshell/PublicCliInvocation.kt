package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import java.io.OutputStream
import sun.misc.Signal

internal fun runPublicCliInvocation(prepare: () -> PublicPreparedOutput, output: OutputStream): PublicShellExit {
    val owner = PublicInvocationOwner()
    val worker = Thread({ runPublicWorker(owner, prepare, output) }, "kast-public-query")
    for ((name, status) in listOf("INT" to PublicShellExit.INTERRUPTED, "TERM" to PublicShellExit.TERMINATED)) {
        // This process entry retains its handlers through exit, including the interval after worker settlement.
        Signal.handle(Signal(name)) { if (owner.cancel(status)) worker.interrupt() }
    }
    worker.start()
    joinPublicWorker(owner, worker)
    return owner.exit
}

private fun runPublicWorker(owner: PublicInvocationOwner, prepare: () -> PublicPreparedOutput, output: OutputStream) {
    try {
        if (owner.active) owner.publish(prepare(), output)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        owner.publishDefect({ publicFailure(ToolRpcFailure.INVOCATION_FAILED) }, output)
    } catch (_: Exception) {
        owner.publishDefect({ publicFailure(ToolRpcFailure.INVOCATION_FAILED) }, output)
    } finally {
        owner.failedIfActive()
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
