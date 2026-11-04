package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.distribution.contract.configuration.CliOutputFormat
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable internal data class ReplayInvocation(val operation: ReplayOperation, val workspace: ReplayWorkspace)

@Serializable
internal enum class ReplayOperation {
    EXECUTE,
    PLAN,
}

@Serializable
internal sealed interface ReplayWorkspace {
    @Serializable @SerialName("fixture") data class Fixture(val root: String, val selection: String?) : ReplayWorkspace

    @Serializable
    @SerialName("foreign")
    data class Foreign(val root: String, val selection: String, val resolved: String) : ReplayWorkspace

    @Serializable @SerialName("invalid") data class Invalid(val selection: String) : ReplayWorkspace
}

/** Keep the production selector without opening another workspace or probing its filesystem. */
internal fun replayWorkspace(selection: PublicCliWorkspace, directory: Path): ReplayWorkspace {
    val root = directory.toAbsolutePath().normalize()
    return when (selection) {
        PublicCliWorkspace.Current -> ReplayWorkspace.Fixture(root.toString(), null)
        is PublicCliWorkspace.Selected ->
            try {
                val resolved = root.resolve(selection.path).normalize()
                if (resolved == root) ReplayWorkspace.Fixture(root.toString(), selection.path)
                else ReplayWorkspace.Foreign(root.toString(), selection.path, resolved.toString())
            } catch (_: InvalidPathException) {
                ReplayWorkspace.Invalid(selection.path)
            }
    }
}

internal fun replayMetadata(
    argv: List<String>,
    stdin: String,
    directory: Path,
    format: CliOutputFormat,
): ReplayAdmissionReceipt {
    val prepared =
        presentPublicOutput(
            preparePublicCli(
                argv,
                stdin.byteInputStream(),
                directory,
                session = { _, _ ->
                    error("REPLAY_NATIVE_COMPOSITION_FORBIDDEN")
                },
            ),
            format,
        )
    return ReplayAdmissionReceipt(
        ReplayAdmissionType.METADATA,
        argv,
        stdin,
        stdout = prepared.text,
        exitStatus = prepared.exit.code,
    )
}
