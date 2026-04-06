package io.github.amichne.kast.cli

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.ImportOptimizeQuery
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.TypeHierarchyQuery

internal sealed interface CliCommand {
    data class Help(val topic: List<String> = emptyList()) : CliCommand
    data object Version : CliCommand
    data class Completion(val shell: CliCompletionShell) : CliCommand
    data class WorkspaceStatus(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceEnsure(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceRefresh(val options: RuntimeCommandOptions, val query: RefreshQuery) : CliCommand
    data class DaemonStart(val options: RuntimeCommandOptions) : CliCommand
    data class DaemonStop(val options: RuntimeCommandOptions) : CliCommand
    data class Capabilities(val options: RuntimeCommandOptions) : CliCommand
    data class ResolveSymbol(val options: RuntimeCommandOptions, val query: SymbolQuery) : CliCommand
    data class FindReferences(val options: RuntimeCommandOptions, val query: ReferencesQuery) : CliCommand
    data class CallHierarchy(val options: RuntimeCommandOptions, val query: CallHierarchyQuery) : CliCommand
    data class TypeHierarchy(val options: RuntimeCommandOptions, val query: TypeHierarchyQuery) : CliCommand
    data class SemanticInsertionPoint(val options: RuntimeCommandOptions, val query: SemanticInsertionQuery) : CliCommand
    data class Diagnostics(val options: RuntimeCommandOptions, val query: DiagnosticsQuery) : CliCommand
    data class Rename(val options: RuntimeCommandOptions, val query: RenameQuery) : CliCommand
    data class ImportOptimize(val options: RuntimeCommandOptions, val query: ImportOptimizeQuery) : CliCommand
    data class ApplyEdits(val options: RuntimeCommandOptions, val query: ApplyEditsQuery) : CliCommand
    data class InternalDaemonRun(val options: RuntimeCommandOptions) : CliCommand
    data class Install(val options: InstallOptions) : CliCommand
    data class InstallSkill(val options: InstallSkillOptions) : CliCommand
    data class Smoke(val options: SmokeOptions) : CliCommand
}
