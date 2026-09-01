package io.github.amichne.kast.cli

import java.nio.file.Path
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import kotlin.system.exitProcess

/** Runtime composition seam; exactly one installed provider must supply the completed CLI graph. */
internal fun interface KastCliComposition {
    /**
     * Proof transition: `installed process environment -> KastCliCompositionConstruction`.
     *
     * Establishes either one completed CLI graph or [KastCliCompositionFailure] as finite data.
     * Raw installation effects remain owned by the service-loaded provider.
     */
    fun create(): KastCliCompositionConstruction
}

internal sealed interface KastCliCompositionConstruction {
    data class Created(val cli: KastCli) : KastCliCompositionConstruction
    data class Rejected(
        val failure: KastCliCompositionFailure,
    ) : KastCliCompositionConstruction
}

internal sealed interface KastCliCompositionFailure {
    /** Stable public bootstrap reason; implementations may preserve a more specific failure. */
    val outputReason: String get() = "composition_invalid"
}

private sealed interface CliBootstrap {
    data class Ready(
        val cli: KastCli,
    ) : CliBootstrap

    data class Rejected(
        val failure: CliBootstrapFailure,
    ) : CliBootstrap
}

private sealed interface CliBootstrapFailure {
    data object CompositionMissing : CliBootstrapFailure
    data object CompositionAmbiguous : CliBootstrapFailure
    data object CompositionInvalid : CliBootstrapFailure
    data class CompositionRejected(
        val failure: KastCliCompositionFailure,
    ) : CliBootstrapFailure
}

/** Process entrypoint for the single Kotlin `kast` executable. */
fun main(args: Array<String>) {
    val exit = when (val bootstrap = loadComposition()) {
        is CliBootstrap.Ready -> bootstrap.cli.execute(args.toList(), Path.of("").toAbsolutePath())
        is CliBootstrap.Rejected -> boundaryExit(
            CliBoundaryExitStatus.BOOTSTRAP,
            bootstrap.failure.outputReason(),
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
        return CliBootstrap.Rejected(CliBootstrapFailure.CompositionInvalid)
    }
    return when (compositions.size) {
        0 -> CliBootstrap.Rejected(CliBootstrapFailure.CompositionMissing)
        1 -> try {
            when (val construction = compositions.single().create()) {
                is KastCliCompositionConstruction.Created -> CliBootstrap.Ready(construction.cli)
                is KastCliCompositionConstruction.Rejected -> CliBootstrap.Rejected(
                    CliBootstrapFailure.CompositionRejected(construction.failure),
                )
            }
        } catch (_: RuntimeException) {
            CliBootstrap.Rejected(CliBootstrapFailure.CompositionInvalid)
        }
        else -> CliBootstrap.Rejected(CliBootstrapFailure.CompositionAmbiguous)
    }
}

private fun CliBootstrapFailure.outputReason(): String = when (this) {
    CliBootstrapFailure.CompositionMissing -> "composition_missing"
    CliBootstrapFailure.CompositionAmbiguous -> "composition_ambiguous"
    CliBootstrapFailure.CompositionInvalid -> "composition_invalid"
    is CliBootstrapFailure.CompositionRejected -> failure.outputReason
}
