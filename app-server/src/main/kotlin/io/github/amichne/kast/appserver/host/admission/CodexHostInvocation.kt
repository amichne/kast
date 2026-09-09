package io.github.amichne.kast.appserver.host.admission

import io.github.amichne.kast.appserver.BrokerOperationalLimits
import io.github.amichne.kast.appserver.core.CanonicalBrokerDirectory
import io.github.amichne.kast.kernel.Refinement

/** Closed process role selected before any broker, Codex, or Desktop effect begins. */
internal enum class CodexHostMode {
    CLI_REMOTE_CLIENT,
    APP_SERVER_STDIO,
}

internal sealed interface CodexHostInvocation {
    val mode: CodexHostMode

    data class Cli(
        val arguments: CodexClientArguments,
    ) : CodexHostInvocation {
        override val mode: CodexHostMode = CodexHostMode.CLI_REMOTE_CLIENT
    }

    data class AppServer(
        val arguments: CodexAppServerArguments,
    ) : CodexHostInvocation {
        override val mode: CodexHostMode = CodexHostMode.APP_SERVER_STDIO
    }

    companion object {
        /**
         * Refines one bounded Codex-compatible argument vector into exactly one host role.
         * The `app-server` token is unique but may follow Codex global options, as it does when
         * Desktop starts its configured CLI executable.
         */
        internal fun admit(
            arguments: List<String>,
        ): Refinement<CodexHostInvocation, CodexHostInvocationFailure> {
            return when (val role = locateAppServerRole(arguments)) {
                CodexHostRoleSelection.Cli -> when (
                    val admitted = CodexClientArguments.admit(arguments)
                ) {
                    is Refinement.Refined -> Refinement.Refined(Cli(admitted.value))
                    is Refinement.Rejected -> Refinement.Rejected(
                        CodexHostInvocationFailure.ClientArguments(admitted.failure),
                    )
                }
                is CodexHostRoleSelection.AppServer -> {
                    when (
                        val admitted = CodexAppServerArguments.admit(
                            arguments.take(role.index),
                            arguments.drop(role.index + 1),
                        )
                    ) {
                        is Refinement.Refined -> Refinement.Refined(AppServer(admitted.value))
                        is Refinement.Rejected -> Refinement.Rejected(
                            CodexHostInvocationFailure.AppServerArguments(admitted.failure),
                        )
                    }
                }
                CodexHostRoleSelection.Ambiguous -> Refinement.Rejected(
                    CodexHostInvocationFailure.AmbiguousRole,
                )
            }
        }

        private fun locateAppServerRole(arguments: List<String>): CodexHostRoleSelection {
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index]
                when {
                    argument == ARGUMENT_DELIMITER -> return CodexHostRoleSelection.Cli
                    argument == APP_SERVER_ROLE -> return CodexHostRoleSelection.AppServer(index)
                    argument in CLI_ONLY_VARIADIC_OPTIONS -> return CodexHostRoleSelection.Cli
                    argument in CODEX_GLOBAL_OPTIONS_WITH_VALUE -> {
                        if (index + 1 >= arguments.size) return CodexHostRoleSelection.Cli
                        index += 2
                    }
                    CODEX_GLOBAL_OPTIONS_WITH_VALUE.any { option ->
                        argument.startsWith("$option=")
                    } -> index += 1
                    argument in CODEX_GLOBAL_FLAG_OPTIONS -> index += 1
                    argument.startsWith('-') -> return if (
                        arguments.drop(index + 1).contains(APP_SERVER_ROLE)
                    ) {
                        CodexHostRoleSelection.Ambiguous
                    } else {
                        CodexHostRoleSelection.Cli
                    }
                    else -> return CodexHostRoleSelection.Cli
                }
            }
            return CodexHostRoleSelection.Cli
        }

        private val CLI_ONLY_VARIADIC_OPTIONS = setOf("-i", "--image")
        private const val ARGUMENT_DELIMITER = "--"
        private const val APP_SERVER_ROLE = "app-server"
    }
}

private sealed interface CodexHostRoleSelection {
    data object Cli : CodexHostRoleSelection
    data class AppServer(val index: Int) : CodexHostRoleSelection
    data object Ambiguous : CodexHostRoleSelection
}

internal sealed interface CodexHostInvocationFailure {
    data class ClientArguments(val failure: CodexArgumentFailure) : CodexHostInvocationFailure
    data class AppServerArguments(
        val failure: CodexAppServerArgumentFailure,
    ) : CodexHostInvocationFailure
    data object AmbiguousRole : CodexHostInvocationFailure
}

internal enum class CodexArgumentFailure {
    REMOTE_OVERRIDE,
    WORKING_DIRECTORY_OVERRIDE,
    TOO_MANY_ARGUMENTS,
    TOO_MANY_BYTES,
    INVALID_CHARACTER,
}

/** A copied argument vector that cannot redirect the TUI away from the host-owned broker. */
internal class CodexClientArguments private constructor(
    val values: List<String>,
) {
    internal fun withOwnedConnection(
        transport: String,
        workingDirectory: CanonicalBrokerDirectory,
    ): List<String> = listOf(
        "--remote",
        transport,
        "--cd",
        workingDirectory.path.toString(),
    ) + values

    companion object {
        internal fun admit(
            arguments: List<String>,
        ): Refinement<CodexClientArguments, CodexArgumentFailure> =
            when (val bounded = admitCommonArguments(arguments)) {
                is Refinement.Rejected -> bounded
                is Refinement.Refined -> if (
                    bounded.value.any { argument ->
                        argument == "--remote" || argument.startsWith("--remote=")
                    }
                ) {
                    Refinement.Rejected(CodexArgumentFailure.REMOTE_OVERRIDE)
                } else if (
                    bounded.value.any { argument ->
                        argument == "-C" || argument.startsWith("-C") ||
                            argument == "--cd" || argument.startsWith("--cd=")
                    }
                ) {
                    Refinement.Rejected(CodexArgumentFailure.WORKING_DIRECTORY_OVERRIDE)
                } else {
                    Refinement.Refined(CodexClientArguments(bounded.value))
                }
            }
    }
}

internal enum class CodexAppServerArgumentFailure {
    TRANSPORT_OVERRIDE,
    REMOTE_OVERRIDE,
    MISSING_OPTION_VALUE,
    OPTION_UNSUPPORTED,
    SUBCOMMAND_UNSUPPORTED,
    TOO_MANY_ARGUMENTS,
    TOO_MANY_BYTES,
    INVALID_CHARACTER,
}

/**
 * Codex App Server options proven not to select a transport or a non-server subcommand.
 * Global options retain their position before `app-server`; role options remain after it.
 */
internal class CodexAppServerArguments private constructor(
    val globalValues: List<String>,
    val roleValues: List<String>,
) {
    internal fun withOwnedTransport(transport: String): List<String> =
        globalValues + APP_SERVER_ROLE + roleValues + listOf("--listen", transport)

    companion object {
        internal fun defaults(): CodexAppServerArguments =
            CodexAppServerArguments(emptyList(), emptyList())

        /** Process-owned settings shared by CLI and desktop attachments. */
        internal fun sharedService(): CodexAppServerArguments = CodexAppServerArguments(
            listOf("-c", SHARED_CODE_MODE_CONFIGURATION),
            listOf(SHARED_ANALYTICS_FLAG),
        )

        internal fun admit(
            globalArguments: List<String>,
            roleArguments: List<String>,
        ): Refinement<CodexAppServerArguments, CodexAppServerArgumentFailure> {
            val all = globalArguments + roleArguments
            val bounded = when (val admission = admitCommonArguments(all)) {
                is Refinement.Refined -> admission.value
                is Refinement.Rejected -> return Refinement.Rejected(
                    when (admission.failure) {
                        CodexArgumentFailure.TOO_MANY_ARGUMENTS ->
                            CodexAppServerArgumentFailure.TOO_MANY_ARGUMENTS
                        CodexArgumentFailure.TOO_MANY_BYTES ->
                            CodexAppServerArgumentFailure.TOO_MANY_BYTES
                        CodexArgumentFailure.INVALID_CHARACTER ->
                            CodexAppServerArgumentFailure.INVALID_CHARACTER
                        CodexArgumentFailure.REMOTE_OVERRIDE,
                        CodexArgumentFailure.WORKING_DIRECTORY_OVERRIDE,
                            -> error("Common argument admission cannot produce an owned-argument override")
                    },
                )
            }
            scanGlobalArguments(globalArguments)?.let { return Refinement.Rejected(it) }
            scanRoleArguments(roleArguments)?.let { return Refinement.Rejected(it) }
            return Refinement.Refined(
                CodexAppServerArguments(
                    globalArguments.toList(),
                    roleArguments.toList(),
                ),
            )
        }

        private fun scanGlobalArguments(
            arguments: List<String>,
        ): CodexAppServerArgumentFailure? {
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index]
                when {
                    argument == "--remote" || argument.startsWith("--remote=") ->
                        return CodexAppServerArgumentFailure.REMOTE_OVERRIDE
                    argument in CODEX_GLOBAL_OPTIONS_WITH_VALUE -> {
                        if (index + 1 >= arguments.size) {
                            return CodexAppServerArgumentFailure.MISSING_OPTION_VALUE
                        }
                        index += 2
                    }
                    CODEX_GLOBAL_OPTIONS_WITH_VALUE.any { option ->
                        argument.startsWith("$option=")
                    } -> {
                        if (argument.substringAfter('=').isEmpty()) {
                            return CodexAppServerArgumentFailure.MISSING_OPTION_VALUE
                        }
                        index += 1
                    }
                    argument in CODEX_GLOBAL_FLAG_OPTIONS -> index += 1
                    else -> return CodexAppServerArgumentFailure.OPTION_UNSUPPORTED
                }
            }
            return null
        }

        private fun scanRoleArguments(
            arguments: List<String>,
        ): CodexAppServerArgumentFailure? {
            var index = 0
            while (index < arguments.size) {
                val argument = arguments[index]
                when {
                    argument == ARGUMENT_DELIMITER ->
                        return CodexAppServerArgumentFailure.SUBCOMMAND_UNSUPPORTED
                    argument in HOST_OWNED_OPTIONS || HOST_OWNED_OPTIONS.any { option ->
                        argument.startsWith("$option=")
                    } -> return CodexAppServerArgumentFailure.TRANSPORT_OVERRIDE
                    argument in ROLE_OPTIONS_WITH_VALUE -> {
                        if (index + 1 >= arguments.size) {
                            return CodexAppServerArgumentFailure.MISSING_OPTION_VALUE
                        }
                        index += 2
                    }
                    ROLE_OPTIONS_WITH_VALUE.any { option ->
                        argument.startsWith("$option=")
                    } -> {
                        if (argument.substringAfter('=').isEmpty()) {
                            return CodexAppServerArgumentFailure.MISSING_OPTION_VALUE
                        }
                        index += 1
                    }
                    argument in ROLE_FLAG_OPTIONS -> index += 1
                    argument.startsWith('-') ->
                        return CodexAppServerArgumentFailure.OPTION_UNSUPPORTED
                    else -> return CodexAppServerArgumentFailure.SUBCOMMAND_UNSUPPORTED
                }
            }
            return null
        }

        private val HOST_OWNED_OPTIONS = setOf(
            "--listen",
            "--stdio",
            "--remote",
            "--code-mode-host",
        )
        private val ROLE_OPTIONS_WITH_VALUE = setOf(
            "-c",
            "--config",
            "--enable",
            "--disable",
            "--ws-auth",
            "--ws-token-file",
            "--ws-token-sha256",
            "--ws-shared-secret-file",
            "--ws-issuer",
            "--ws-audience",
            "--ws-max-clock-skew-seconds",
        )
        private val ROLE_FLAG_OPTIONS = setOf(
            "--strict-config",
            "--analytics-default-enabled",
            "-h",
            "--help",
        )
        private const val ARGUMENT_DELIMITER = "--"
        private const val APP_SERVER_ROLE = "app-server"
    }
}

private fun admitCommonArguments(
    arguments: List<String>,
): Refinement<List<String>, CodexArgumentFailure> = when {
    arguments.size > MAXIMUM_ARGUMENT_COUNT -> Refinement.Rejected(
        CodexArgumentFailure.TOO_MANY_ARGUMENTS,
    )
    arguments.any { argument -> '\u0000' in argument } -> Refinement.Rejected(
        CodexArgumentFailure.INVALID_CHARACTER,
    )
    arguments.sumOf { argument ->
        argument.toByteArray(Charsets.UTF_8).size.toLong()
    } > MAXIMUM_ARGUMENT_BYTES -> Refinement.Rejected(CodexArgumentFailure.TOO_MANY_BYTES)
    else -> Refinement.Refined(arguments.toList())
}

private const val MAXIMUM_ARGUMENT_COUNT = BrokerOperationalLimits.maximumHostArgumentCount
private const val MAXIMUM_ARGUMENT_BYTES = BrokerOperationalLimits.maximumHostArgumentBytes

private val CODEX_GLOBAL_OPTIONS_WITH_VALUE = setOf(
    "-c",
    "--config",
    "--enable",
    "--disable",
    "--remote",
    "--remote-auth-token-env",
    "-m",
    "--model",
    "--local-provider",
    "-p",
    "--profile",
    "-s",
    "--sandbox",
    "-C",
    "--cd",
    "--add-dir",
    "-a",
    "--ask-for-approval",
)

private val CODEX_GLOBAL_FLAG_OPTIONS = setOf(
    "--strict-config",
    "--oss",
    "--approve-for-me",
    "--dangerously-bypass-approvals-and-sandbox",
    "--dangerously-bypass-hook-trust",
    "--search",
    "--no-alt-screen",
    "-h",
    "--help",
    "-V",
    "--version",
)

/** An attachment has proven that it cannot alter the shared process configuration. */
internal sealed interface CodexServiceInvocation {
    data class Cli(val arguments: CodexClientArguments) : CodexServiceInvocation
    data object Stdio : CodexServiceInvocation

    companion object {
        fun admit(invocation: CodexHostInvocation): Refinement<CodexServiceInvocation, CodexServiceArgumentFailure> {
            return when (invocation) {
                is CodexHostInvocation.Cli -> Refinement.Refined(Cli(invocation.arguments))
                is CodexHostInvocation.AppServer -> {
                    val segments = listOf(invocation.arguments.globalValues, invocation.arguments.roleValues)
                    for (segment in segments) {
                        var index = 0
                        while (index < segment.size) {
                            when (segment[index]) {
                                "-c", "--config" -> {
                                    if (segment.getOrNull(index + 1) != SHARED_CODE_MODE_CONFIGURATION) {
                                        return Refinement.Rejected(CodexServiceArgumentFailure.PROCESS_CONFIGURATION_CONFLICT)
                                    }
                                    index += 2
                                }
                                "--config=$SHARED_CODE_MODE_CONFIGURATION", SHARED_ANALYTICS_FLAG -> index++
                                else -> return Refinement.Rejected(CodexServiceArgumentFailure.PROCESS_CONFIGURATION_CONFLICT)
                            }
                        }
                    }
                    Refinement.Refined(Stdio)
                }
            }
        }
    }
}

internal enum class CodexServiceArgumentFailure { PROCESS_CONFIGURATION_CONFLICT }
private const val SHARED_CODE_MODE_CONFIGURATION = "features.code_mode_host=true"
private const val SHARED_ANALYTICS_FLAG = "--analytics-default-enabled"
