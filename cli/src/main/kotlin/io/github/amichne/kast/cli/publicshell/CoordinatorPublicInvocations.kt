package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.AdmittedPublicInvocation
import io.github.amichne.kast.appserver.DaemonOperationClient
import io.github.amichne.kast.appserver.PublicInvocationExecutor
import io.github.amichne.kast.appserver.PublicInvocationInput
import io.github.amichne.kast.appserver.PublicInvocationOutcome
import io.github.amichne.kast.cli.MAXIMUM_PROTOCOL_TEXT_LENGTH
import io.github.amichne.kast.cli.direct.InvocationWitness
import io.github.amichne.kast.cli.rpc.ToolRpcFailure
import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import io.github.amichne.kast.kernel.Refinement
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path

/**
 * The public shell owner inside the coordinator: the native process's lowering, admission, presentation and defect
 * rule, with the caller's directory, environment and stdin supplied explicitly. [product] is the installation whose
 * Hosts serve the caller.
 */
internal class CoordinatorPublicInvocations(private val product: Path) : PublicInvocationExecutor {
    override fun invoke(
        invocation: AdmittedPublicInvocation,
        semantic: (installation: Path, environment: Map<String, String>) -> DaemonOperationClient,
        input: PublicInvocationInput,
    ): PublicInvocationOutcome {
        val diagnostics = BoundedDiagnostics()
        val arguments = invocation.arguments
        if (arguments.firstOrNull() in processLocalNamespaces) {
            diagnostics.add("kast: hook commands read process-local state and are not served by the coordinator.")
            return presentPublicOutput(publicFailure(ToolRpcFailure.INVALID_COMMAND), CliOutputFormat.JSON)
                .outcome(diagnostics)
        }
        val format =
            when (val selected = selectPublicOutputFormat(arguments, invocation.environment[PUBLIC_OUTPUT_SETTING])) {
                is Refinement.Rejected ->
                    return presentPublicOutput(selected.failure, CliOutputFormat.JSON).outcome(diagnostics)
                is Refinement.Refined -> selected.value
            }
        return try {
            presentPublicOutput(
                    preparePublicCli(
                        arguments,
                        FrontendStdin(input),
                        invocation.directory,
                        session = { root, catalog ->
                            coordinatedPublicSession(
                                product,
                                root,
                                catalog,
                                invocation.environment,
                                read = { binding -> semantic(binding.installation, binding.environment) },
                                witness = InvocationWitness.CoordinatorLease,
                            )
                        },
                        diagnostic = diagnostics::add,
                        jqExecutor = PublicJqProcess(invocation.directory, emptyMap()),
                    ),
                    format,
                )
                .outcome(diagnostics)
        } catch (interrupted: InterruptedException) {
            throw interrupted
        } catch (_: Exception) {
            // The native worker's defect rule: the failure document is delivered and the status stays DEFECT.
            presentPublicOutput(publicFailure(ToolRpcFailure.INVOCATION_FAILED), format)
                .copy(exit = PublicShellExit.DEFECT)
                .outcome(diagnostics)
        }
    }
}

private fun PublicPreparedOutput.outcome(diagnostics: BoundedDiagnostics) =
    PublicInvocationOutcome(exit.code, text, diagnostics.lines)

/** Reads the caller's stdin from the frontend only when the admitted command asks for it. */
private class FrontendStdin(private val input: PublicInvocationInput) : InputStream() {
    private var delivered: InputStream? = null

    private fun delivered(): InputStream =
        delivered ?: ByteArrayInputStream(input.read(MAXIMUM_PROTOCOL_TEXT_LENGTH + 1)).also { delivered = it }

    override fun read(): Int = delivered().read()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = delivered().read(buffer, offset, length)
}

/** Parser and input diagnostics are short guidance lines; the bound keeps a reply from carrying unbounded text. */
private class BoundedDiagnostics {
    private val retained = mutableListOf<String>()
    val lines: List<String>
        get() = retained.toList()

    fun add(line: String) {
        if (retained.size < MAXIMUM_DIAGNOSTIC_LINES) retained += line.take(MAXIMUM_DIAGNOSTIC_CHARACTERS)
    }
}

private val processLocalNamespaces = setOf("hook", "workspace")
private const val MAXIMUM_DIAGNOSTIC_LINES = 32
private const val MAXIMUM_DIAGNOSTIC_CHARACTERS = 4_096
