package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.contract.result.ApplyEditsResult
import io.github.amichne.kast.api.contract.BackendCapabilities
import io.github.amichne.kast.api.contract.result.DiagnosticsResult
import io.github.amichne.kast.api.contract.result.FileOutlineResult
import io.github.amichne.kast.api.contract.result.ImportOptimizeResult
import io.github.amichne.kast.api.contract.result.RefreshResult
import io.github.amichne.kast.api.contract.result.ReferencesResult
import io.github.amichne.kast.api.contract.result.RenameResult
import io.github.amichne.kast.api.contract.result.CallHierarchyResult
import io.github.amichne.kast.api.contract.SemanticInsertionResult
import io.github.amichne.kast.api.contract.result.SymbolResult
import io.github.amichne.kast.api.contract.result.TypeHierarchyResult
import io.github.amichne.kast.api.contract.result.WorkspaceSymbolResult
import io.github.amichne.kast.cli.results.DaemonStopResult
import io.github.amichne.kast.cli.results.InstallResult
import io.github.amichne.kast.cli.skill.InstallSkillResult
import io.github.amichne.kast.cli.SmokeReport
import io.github.amichne.kast.cli.results.WorkspaceEnsureResult
import io.github.amichne.kast.cli.results.WorkspaceStatusResult
import kotlinx.serialization.json.Json

internal fun defaultCliJson(): Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun writeCliJson(
    output: Appendable,
    value: Any,
    json: Json,
) {
    val encodedValue = when (value) {
        is WorkspaceStatusResult -> json.encodeToString(value)
        is WorkspaceEnsureResult -> json.encodeToString(value)
        is DaemonStopResult -> json.encodeToString(value)
        is InstallResult -> json.encodeToString(value)
        is InstallSkillResult -> json.encodeToString(value)
        is BackendCapabilities -> json.encodeToString(value)
        is SymbolResult -> json.encodeToString(value)
        is ReferencesResult -> json.encodeToString(value)
        is DiagnosticsResult -> json.encodeToString(value)
        is FileOutlineResult -> json.encodeToString(value)
        is WorkspaceSymbolResult -> json.encodeToString(value)
        is SemanticInsertionResult -> json.encodeToString(value)
        is RenameResult -> json.encodeToString(value)
        is ImportOptimizeResult -> json.encodeToString(value)
        is ApplyEditsResult -> json.encodeToString(value)
        is RefreshResult -> json.encodeToString(value)
        is CallHierarchyResult -> json.encodeToString(value)
        is TypeHierarchyResult -> json.encodeToString(value)
        is SmokeReport -> json.encodeToString(value)
        is CliErrorResponse -> json.encodeToString(value)
        else -> error("Unsupported CLI output type: ${value::class.java.name}")
    }
    output.append(encodedValue)
    output.append('\n')
}

internal fun cliErrorFromThrowable(throwable: Throwable): CliErrorResponse {
    return when (throwable) {
        is CliFailure -> CliErrorResponse(
            code = throwable.code,
            message = throwable.message,
            details = throwable.details,
        )

        is AnalysisException -> CliErrorResponse(
            code = throwable.errorCode,
            message = throwable.message,
            details = throwable.details,
        )

        else -> CliErrorResponse(
            code = "CLI_INTERNAL_ERROR",
            message = throwable.message ?: throwable::class.java.simpleName,
        )
    }
}
