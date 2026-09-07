package io.github.amichne.kast.cli

import io.github.amichne.kast.cli.command.CliRequestDocumentInput
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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

    fun inspect(start: Path): CliExit = when (val result = create()) {
        is KastCliCompositionConstruction.Created -> result.cli.execute(emptyList(), start)
        is KastCliCompositionConstruction.Rejected -> boundaryExit(CliBoundaryExitStatus.BOOTSTRAP, result.failure.outputReason)
    }
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

    data class Inspected(val exit: CliExit) : CliBootstrap

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
    val exit = when (val bootstrap = loadComposition(args.isEmpty())) {
        is CliBootstrap.Ready -> bootstrap.cli.execute(
            args.toList(),
            Path.of("").toAbsolutePath(),
            CliRequestDocumentInput.Deferred(::readCanonicalRequestInput),
        )
        is CliBootstrap.Inspected -> bootstrap.exit
        is CliBootstrap.Rejected -> boundaryExit(
            CliBoundaryExitStatus.BOOTSTRAP,
            bootstrap.failure.outputReason(),
        )
    }
    when (exit) {
        is CliExit.Delegated -> Unit
        is CliExit.Complete -> System.out.println(exit.document.value)
        is CliExit.Qualified -> System.out.println(exit.document.value)
        is CliExit.OperationRejected -> System.out.println(exit.document.value)
        is CliExit.BoundaryRejected -> System.err.println(exit.document.value)
    }
    exitProcess(exit.code)
}

private fun readCanonicalRequestInput(): CliRequestDocumentInput {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    try {
        while (true) {
            val count = System.`in`.read(buffer)
            if (count < 0) break
            if (bytes.size() + count > MAXIMUM_REQUEST_DOCUMENT_BYTES) {
                return CliRequestDocumentInput.Rejected
            }
            bytes.write(buffer, 0, count)
        }
    } catch (_: IOException) {
        return CliRequestDocumentInput.Rejected
    }
    if (bytes.size() == 0) return CliRequestDocumentInput.Absent
    val document = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray()))
            .toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        return CliRequestDocumentInput.Rejected
    }
    return if (document.isBlank()) CliRequestDocumentInput.Rejected
    else CliRequestDocumentInput.Provided(document)
}

private const val MAXIMUM_REQUEST_DOCUMENT_BYTES = 4 * 1_024 * 1_024

/**
 * Proof transition: installed service providers -> `CliBootstrap`.
 *
 * Establishes exactly one completed CLI composition. [CliBootstrapFailure] is the closed expected
 * failure. Service-provider iteration is permitted only at this installed-product boundary.
 */
private fun loadComposition(passive: Boolean): CliBootstrap {
    val compositions = try {
        ServiceLoader.load(KastCliComposition::class.java).toList()
    } catch (_: ServiceConfigurationError) {
        return CliBootstrap.Rejected(CliBootstrapFailure.CompositionInvalid)
    }
    return when (compositions.size) {
        0 -> CliBootstrap.Rejected(CliBootstrapFailure.CompositionMissing)
        1 -> try {
            if (passive) return CliBootstrap.Inspected(compositions.single().inspect(Path.of("").toAbsolutePath()))
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
