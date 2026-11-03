package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.appserver.DaemonOperationFailure
import io.github.amichne.kast.appserver.ide.CanonicalRoot
import io.github.amichne.kast.appserver.ide.CanonicalRootDiscovery
import io.github.amichne.kast.appserver.ide.CanonicalRootFailure
import io.github.amichne.kast.appserver.ide.FilesystemCanonicalRootDiscovery
import io.github.amichne.kast.cli.direct.ClientInstallationFailure
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Required
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable private data class SessionStartEvent(val cwd: String)

@Serializable private class HookAccepted

@Serializable
private data class HookRejected(
    val failure: HookFailure,
    val cause: HookPreparationFailure? = null,
    @Required val type: String = "rejected",
)

@Serializable
internal sealed interface HookPreparationFailure {
    @Serializable
    @SerialName("installation")
    data class Installation(val failure: ClientInstallationFailure) : HookPreparationFailure

    @Serializable @SerialName("stopped") data object Stopped : HookPreparationFailure

    @Serializable @SerialName("unproven") data object Unproven : HookPreparationFailure

    @Serializable @SerialName("native") data class Native(val failure: DaemonOperationFailure) : HookPreparationFailure
}

@Serializable
private enum class HookFailure {
    INVALID_EVENT,
    INPUT_UNAVAILABLE,
    INVALID_COMMAND,
    INVALID_WORKSPACE,
    PREPARATION_REJECTED,
}

private val hookJson = Json { ignoreUnknownKeys = true }
private const val MAX_HOOK_EVENT_BYTES = 65536

/** All harness adapters delegate project selection and IDE effects to this owner. */
internal fun preparePublicHook(
    argv: List<String>,
    input: InputStream,
    prepare: (CanonicalRoot) -> Refinement<Unit, HookPreparationFailure> = ::prepareInstalledHook,
): PublicPreparedOutput {
    if (argv == listOf("hook", "--help") || argv == listOf("hook", "session-start", "--help"))
        return PublicPreparedOutput(
            "kast hook session-start [--workspace ABSOLUTE_PATH]\n" +
                "Without --workspace, read a host event with cwd from stdin.",
            PublicShellExit.COMPLETE,
            PublicOutputContent.HELP,
        )
    if (argv.take(2) != listOf("hook", "session-start"))
        return hookRejected(HookFailure.INVALID_COMMAND, PublicShellExit.INPUT)
    val workspace =
        when (val admitted = readHookWorkspace(argv, input)) {
            is Refinement.Refined -> admitted.value
            is Refinement.Rejected -> return hookInputRejected(admitted.failure)
        }
    return when (val root = FilesystemCanonicalRootDiscovery.discover(workspace.path)) {
        is CanonicalRootDiscovery.Rejected ->
            if (root.failure == CanonicalRootFailure.ROOT_MARKER_NOT_FOUND) hookAccepted()
            else
                hookRejected(
                    HookFailure.INVALID_WORKSPACE,
                    PublicShellExit.WORKSPACE,
                    HookPreparationFailure.Native(DaemonOperationFailure.Root(root.failure)),
                )
        is CanonicalRootDiscovery.Discovered ->
            when (val result = prepare(root.root)) {
                is Refinement.Refined -> hookAccepted()
                is Refinement.Rejected ->
                    hookRejected(HookFailure.PREPARATION_REJECTED, PublicShellExit.RUNTIME, result.failure)
            }
    }
}

private class HookWorkspace private constructor(val path: Path) {
    companion object {
        fun admit(raw: String): Refinement<HookWorkspace, HookFailure> =
            try {
                val path = Path.of(raw)
                val invalidText =
                    raw.isEmpty() ||
                        raw.length > MAX_HOOK_PATH_LENGTH ||
                        raw.any { it.code < FIRST_PRINTABLE_CHARACTER }
                if (invalidText || !path.isAbsolute) Refinement.Rejected(HookFailure.INVALID_EVENT)
                else Refinement.Refined(HookWorkspace(path))
            } catch (_: InvalidPathException) {
                Refinement.Rejected(HookFailure.INVALID_EVENT)
            }
    }
}

private const val MAX_HOOK_PATH_LENGTH = 4096
private const val FIRST_PRINTABLE_CHARACTER = 32
private const val SELECTED_WORKSPACE_ARGUMENT_COUNT = 4

private fun readHookWorkspace(argv: List<String>, input: InputStream): Refinement<HookWorkspace, HookFailure> =
    when {
        argv.size == SELECTED_WORKSPACE_ARGUMENT_COUNT && argv[2] == "--workspace" -> HookWorkspace.admit(argv.last())
        argv.size == 2 ->
            when (val event = readSessionStartEvent(input)) {
                is Refinement.Rejected -> event
                is Refinement.Refined -> HookWorkspace.admit(event.value.cwd)
            }
        else -> Refinement.Rejected(HookFailure.INVALID_COMMAND)
    }

private fun readSessionStartEvent(input: InputStream): Refinement<SessionStartEvent, HookFailure> =
    try {
        val bytes = input.readNBytes(MAX_HOOK_EVENT_BYTES + 1)
        if (bytes.size > MAX_HOOK_EVENT_BYTES) Refinement.Rejected(HookFailure.INVALID_EVENT)
        else
            Refinement.Refined(
                hookJson.decodeFromString<SessionStartEvent>(bytes.decodeToString(throwOnInvalidSequence = true))
            )
    } catch (_: IOException) {
        Refinement.Rejected(HookFailure.INPUT_UNAVAILABLE)
    } catch (_: SerializationException) {
        Refinement.Rejected(HookFailure.INVALID_EVENT)
    } catch (_: CharacterCodingException) {
        Refinement.Rejected(HookFailure.INVALID_EVENT)
    }

private fun hookAccepted() = PublicPreparedOutput(hookJson.encodeToString(HookAccepted()), PublicShellExit.COMPLETE)

private fun hookInputRejected(failure: HookFailure) =
    hookRejected(failure, if (failure == HookFailure.INPUT_UNAVAILABLE) PublicShellExit.IO else PublicShellExit.INPUT)

private fun hookRejected(failure: HookFailure, exit: PublicShellExit, cause: HookPreparationFailure? = null) =
    PublicPreparedOutput(hookJson.encodeToString(HookRejected(failure, cause)), exit)
