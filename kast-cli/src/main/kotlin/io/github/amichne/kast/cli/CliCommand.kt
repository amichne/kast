package io.github.amichne.kast.cli

import io.github.amichne.kast.api.contract.ApplyEditsQuery
import io.github.amichne.kast.api.contract.CallHierarchyQuery
import io.github.amichne.kast.api.contract.CodeActionsQuery
import io.github.amichne.kast.api.contract.CompletionsQuery
import io.github.amichne.kast.api.contract.DiagnosticsQuery
import io.github.amichne.kast.api.contract.FileOutlineQuery
import io.github.amichne.kast.api.contract.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.ImplementationsQuery
import io.github.amichne.kast.api.contract.ReferencesQuery
import io.github.amichne.kast.api.contract.RefreshQuery
import io.github.amichne.kast.api.contract.RenameQuery
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.SymbolQuery
import io.github.amichne.kast.api.contract.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.runtime.RuntimeLifecycleRequest
import io.github.amichne.kast.cli.runtime.RuntimeSelection
import io.github.amichne.kast.cli.skill.SkillWrapperName

internal sealed interface CliCommand {
    data class Help(val topic: List<String> = emptyList()) : CliCommand
    data object Version : CliCommand
    data class Completion(val shell: CliCompletionShell) : CliCommand
    data class WorkspaceStatus(val request: RuntimeLifecycleRequest) : CliCommand
    data class WorkspaceEnsure(val request: RuntimeLifecycleRequest) : CliCommand
    data class WorkspaceRefresh(val request: RuntimeLifecycleRequest, val query: RefreshQuery) : CliCommand
    data class WorkspaceStop(val request: RuntimeLifecycleRequest) : CliCommand
    data class Capabilities(val runtime: RuntimeSelection) : CliCommand
    data class ResolveSymbol(val runtime: RuntimeSelection, val query: SymbolQuery) : CliCommand
    data class FindReferences(val runtime: RuntimeSelection, val query: ReferencesQuery) : CliCommand
    data class CallHierarchy(val runtime: RuntimeSelection, val query: CallHierarchyQuery) : CliCommand
    data class TypeHierarchy(val runtime: RuntimeSelection, val query: TypeHierarchyQuery) : CliCommand
    data class SemanticInsertionPoint(val runtime: RuntimeSelection, val query: SemanticInsertionQuery) : CliCommand
    data class Diagnostics(val runtime: RuntimeSelection, val query: DiagnosticsQuery) : CliCommand
    data class FileOutline(val runtime: RuntimeSelection, val query: FileOutlineQuery) : CliCommand
    data class WorkspaceSymbol(val runtime: RuntimeSelection, val query: WorkspaceSymbolQuery) : CliCommand
    data class WorkspaceFiles(val runtime: RuntimeSelection, val query: WorkspaceFilesQuery) : CliCommand
    data class Implementations(val runtime: RuntimeSelection, val query: ImplementationsQuery) : CliCommand
    data class CodeActions(val runtime: RuntimeSelection, val query: CodeActionsQuery) : CliCommand
    data class Completions(val runtime: RuntimeSelection, val query: CompletionsQuery) : CliCommand
    data class Rename(val runtime: RuntimeSelection, val query: RenameQuery) : CliCommand
    data class ImportOptimize(val runtime: RuntimeSelection, val query: ImportOptimizeQuery) : CliCommand
    data class ApplyEdits(val runtime: RuntimeSelection, val query: ApplyEditsQuery) : CliCommand
    data class Install(val options: InstallOptions) : CliCommand
    data class InstallSkill(val options: InstallSkillOptions) : CliCommand
    data class Smoke(val options: SmokeOptions) : CliCommand
    data class Skill(val name: SkillWrapperName, val rawInput: String) : CliCommand
    data class EvalSkill(val options: EvalSkillOptions) : CliCommand
}

internal data class EvalSkillOptions(
    val skillDir: java.nio.file.Path,
    val compareBaseline: java.nio.file.Path? = null,
    val format: EvalOutputFormat = EvalOutputFormat.JSON,
)

internal enum class EvalOutputFormat { JSON, MARKDOWN }
