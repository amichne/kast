package io.github.amichne.kast.cli.publicshell

import io.github.amichne.kast.kernel.ElapsedTimeAllowance
import io.github.amichne.kast.kernel.Refinement
import java.io.IOException
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

@Serializable
internal enum class HookFailure {
    INVALID_EVENT,
    INPUT_UNAVAILABLE,
    INVALID_COMMAND,
    INVALID_WORKSPACE,
    PREPARATION_REJECTED,
    PREPARATION_TIMED_OUT,
}

internal enum class HookHost {
    CLI,
    CODEX,
    COPILOT,
}

internal data class HookArguments(val workspace: Path, val wait: HookWait, val host: HookHost)

@Serializable private data class SessionStartEvent(val cwd: String)

private val eventJson = Json { ignoreUnknownKeys = true }
private const val MAX_HOOK_EVENT_BYTES = 65536

internal fun readHookArguments(
    argv: List<String>,
    input: InputStream,
    waitCommand: Boolean,
): Refinement<HookArguments, HookFailure> {
    val options =
        when (val parsed = readHookOptions(argv.drop(2))) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    val workspace =
        when (val parsed = readHookWorkspace(options["--workspace"], input, waitCommand)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    val wait =
        when (val parsed = readHookWait(options["--wait"], waitCommand)) {
            is Refinement.Refined -> parsed.value
            is Refinement.Rejected -> return parsed
        }
    return when (val host = readHookHost(options["--host"])) {
        is Refinement.Refined -> Refinement.Refined(HookArguments(workspace, wait, host.value))
        is Refinement.Rejected -> host
    }
}

private fun readHookOptions(tail: List<String>): Refinement<Map<String, String>, HookFailure> {
    if (tail.size % 2 != 0) return Refinement.Rejected(HookFailure.INVALID_COMMAND)
    val options = linkedMapOf<String, String>()
    for (pair in tail.chunked(2)) {
        if (pair[0] !in setOf("--workspace", "--wait", "--host") || options.put(pair[0], pair[1]) != null)
            return Refinement.Rejected(HookFailure.INVALID_COMMAND)
    }
    return Refinement.Refined(options)
}

private fun readHookWorkspace(
    selected: String?,
    input: InputStream,
    waitCommand: Boolean,
): Refinement<Path, HookFailure> =
    when {
        selected != null -> admitHookPath(selected)
        waitCommand -> admitHookPath(Path.of("").toAbsolutePath().toString())
        else ->
            when (val event = readSessionStartEvent(input)) {
                is Refinement.Refined -> admitHookPath(event.value.cwd)
                is Refinement.Rejected -> event
            }
    }

private fun admitHookPath(raw: String): Refinement<Path, HookFailure> =
    try {
        val path = Path.of(raw)
        val invalidText = raw.isEmpty() || raw.length > 4096 || raw.any { it.code < 32 }
        if (invalidText || !path.isAbsolute) Refinement.Rejected(HookFailure.INVALID_EVENT)
        else Refinement.Refined(path)
    } catch (_: InvalidPathException) {
        Refinement.Rejected(HookFailure.INVALID_EVENT)
    }

private fun readHookWait(raw: String?, waitCommand: Boolean): Refinement<HookWait, HookFailure> =
    when (raw) {
        null -> Refinement.Refined(HookWait.Configured)
        "none" ->
            if (waitCommand) Refinement.Rejected(HookFailure.INVALID_COMMAND)
            else Refinement.Refined(HookWait.Background)
        else ->
            when (val parsed = ElapsedTimeAllowance.parseSetting(raw)) {
                is Refinement.Refined -> Refinement.Refined(HookWait.Selected(parsed.value))
                is Refinement.Rejected -> Refinement.Rejected(HookFailure.INVALID_COMMAND)
            }
    }

private fun readHookHost(raw: String?): Refinement<HookHost, HookFailure> =
    when (raw) {
        null -> Refinement.Refined(HookHost.CLI)
        "codex" -> Refinement.Refined(HookHost.CODEX)
        "copilot" -> Refinement.Refined(HookHost.COPILOT)
        else -> Refinement.Rejected(HookFailure.INVALID_COMMAND)
    }

private fun readSessionStartEvent(input: InputStream): Refinement<SessionStartEvent, HookFailure> =
    try {
        val bytes = input.readNBytes(MAX_HOOK_EVENT_BYTES + 1)
        if (bytes.size > MAX_HOOK_EVENT_BYTES) Refinement.Rejected(HookFailure.INVALID_EVENT)
        else
            Refinement.Refined(
                eventJson.decodeFromString<SessionStartEvent>(bytes.decodeToString(throwOnInvalidSequence = true))
            )
    } catch (_: IOException) {
        Refinement.Rejected(HookFailure.INPUT_UNAVAILABLE)
    } catch (_: SerializationException) {
        Refinement.Rejected(HookFailure.INVALID_EVENT)
    } catch (_: CharacterCodingException) {
        Refinement.Rejected(HookFailure.INVALID_EVENT)
    }
