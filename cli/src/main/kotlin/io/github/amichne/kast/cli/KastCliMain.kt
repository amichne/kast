package io.github.amichne.kast.cli

import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlin.system.exitProcess

/** Runtime composition seam; exactly one installed provider must supply the completed CLI graph. */
fun interface KastCliComposition {
    fun create(): KastCli
}

private sealed interface CliBootstrap {
    data class Ready(
        val cli: KastCli,
    ) : CliBootstrap

    data class Rejected(
        val failure: CliBootstrapFailure,
    ) : CliBootstrap
}

private enum class CliBootstrapFailure {
    COMPOSITION_MISSING,
    COMPOSITION_AMBIGUOUS,
    COMPOSITION_INVALID,
}

/** Process entrypoint for the single Kotlin `kast` executable. */
fun main(args: Array<String>) {
    val exit = when (val bootstrap = loadComposition()) {
        is CliBootstrap.Ready -> bootstrap.cli.execute(args.toList(), Path.of("").toAbsolutePath())
        is CliBootstrap.Rejected -> boundaryExit(
            CliBoundaryExitStatus.BOOTSTRAP,
            bootstrap.failure.name.lowercase(),
        )
    }
    when (exit) {
        is CliExit.Complete,
        is CliExit.Qualified,
        is CliExit.OperationRejected,
        -> System.out.println(exit.document.value)
        is CliExit.BoundaryRejected -> System.err.println(exit.document.value)
    }
    exitProcess(exit.code)
}

/**
 * Proof transition: installed service providers -> `CliBootstrap`.
 *
 * Establishes exactly one completed CLI composition. [CliBootstrapFailure] is the closed expected
 * failure. Service-provider iteration is permitted only at this installed-product boundary.
 */
private fun loadComposition(): CliBootstrap {
    val compositions = try {
        ServiceLoader.load(KastCliComposition::class.java).toList()
    } catch (_: ServiceConfigurationError) {
        return CliBootstrap.Rejected(CliBootstrapFailure.COMPOSITION_INVALID)
    }
    return when (compositions.size) {
        0 -> CliBootstrap.Rejected(CliBootstrapFailure.COMPOSITION_MISSING)
        1 -> try {
            CliBootstrap.Ready(compositions.single().create())
        } catch (_: RuntimeException) {
            CliBootstrap.Rejected(CliBootstrapFailure.COMPOSITION_INVALID)
        }
        else -> CliBootstrap.Rejected(CliBootstrapFailure.COMPOSITION_AMBIGUOUS)
    }
}
