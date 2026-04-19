package io.github.amichne.kast.cli

import io.github.amichne.kast.api.ApplyEditsQuery
import io.github.amichne.kast.api.CallHierarchyQuery
import io.github.amichne.kast.api.CodeActionsQuery
import io.github.amichne.kast.api.CompletionsQuery
import io.github.amichne.kast.api.DiagnosticsQuery
import io.github.amichne.kast.api.FileOutlineQuery
import io.github.amichne.kast.api.ImportOptimizeQuery
import io.github.amichne.kast.api.ImplementationsQuery
import io.github.amichne.kast.api.ReferencesQuery
import io.github.amichne.kast.api.RefreshQuery
import io.github.amichne.kast.api.RenameQuery
import io.github.amichne.kast.api.SemanticInsertionQuery
import io.github.amichne.kast.api.SymbolQuery
import io.github.amichne.kast.api.TypeHierarchyQuery
import io.github.amichne.kast.api.WorkspaceFilesQuery
import io.github.amichne.kast.api.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.skill.SkillWrapperName

internal sealed interface CliCommand {
    data class Help(val topic: List<String> = emptyList()) : CliCommand
    data object Version : CliCommand
    data class Completion(val shell: CliCompletionShell) : CliCommand
    data class WorkspaceStatus(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceEnsure(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceRefresh(val options: RuntimeCommandOptions, val query: RefreshQuery) : CliCommand
    data class WorkspaceStop(val options: RuntimeCommandOptions) : CliCommand
    data class Capabilities(val options: RuntimeCommandOptions) : CliCommand
    data class ResolveSymbol(val options: RuntimeCommandOptions, val query: SymbolQuery) : CliCommand
    data class FindReferences(val options: RuntimeCommandOptions, val query: ReferencesQuery) : CliCommand
    data class CallHierarchy(val options: RuntimeCommandOptions, val query: CallHierarchyQuery) : CliCommand
    data class TypeHierarchy(val options: RuntimeCommandOptions, val query: TypeHierarchyQuery) : CliCommand
    data class SemanticInsertionPoint(val options: RuntimeCommandOptions, val query: SemanticInsertionQuery) : CliCommand
    data class Diagnostics(val options: RuntimeCommandOptions, val query: DiagnosticsQuery) : CliCommand
    data class FileOutline(val options: RuntimeCommandOptions, val query: FileOutlineQuery) : CliCommand
    data class WorkspaceSymbol(val options: RuntimeCommandOptions, val query: WorkspaceSymbolQuery) : CliCommand
    data class WorkspaceFiles(val options: RuntimeCommandOptions, val query: WorkspaceFilesQuery) : CliCommand
    data class Implementations(val options: RuntimeCommandOptions, val query: ImplementationsQuery) : CliCommand
    data class CodeActions(val options: RuntimeCommandOptions, val query: CodeActionsQuery) : CliCommand
    data class Completions(val options: RuntimeCommandOptions, val query: CompletionsQuery) : CliCommand
    data class Rename(val options: RuntimeCommandOptions, val query: RenameQuery) : CliCommand
    data class ImportOptimize(val options: RuntimeCommandOptions, val query: ImportOptimizeQuery) : CliCommand
    data class ApplyEdits(val options: RuntimeCommandOptions, val query: ApplyEditsQuery) : CliCommand
    data class InternalDaemonRun(val options: RuntimeCommandOptions) : CliCommand
    data class Install(val options: InstallOptions) : CliCommand
    data class InstallSkill(val options: InstallSkillOptions) : CliCommand
    data class Smoke(val options: SmokeOptions) : CliCommand
    data class Demo(val options: DemoOptions) : CliCommand
    data class Skill(val name: SkillWrapperName, val rawInput: String) : CliCommand
    data class EvalSkill(val options: EvalSkillOptions) : CliCommand
}

internal data class EvalSkillOptions(
    val skillDir: java.nio.file.Path,
    val compareBaseline: java.nio.file.Path? = null,
    val format: EvalOutputFormat = EvalOutputFormat.JSON,
)

internal enum class EvalOutputFormat { JSON, MARKDOWN }
