package io.github.amichne.kast.cli.tty

import io.github.amichne.kast.api.contract.query.ApplyEditsQuery
import io.github.amichne.kast.api.contract.query.CallHierarchyQuery
import io.github.amichne.kast.api.contract.query.CodeActionsQuery
import io.github.amichne.kast.api.contract.query.CompletionsQuery
import io.github.amichne.kast.api.contract.query.DiagnosticsQuery
import io.github.amichne.kast.api.contract.query.FileOutlineQuery
import io.github.amichne.kast.api.contract.query.ImportOptimizeQuery
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RefreshQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.SemanticInsertionQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSymbolQuery
import io.github.amichne.kast.cli.options.DaemonStartOptions
import io.github.amichne.kast.cli.options.InstallCopilotExtensionOptions
import io.github.amichne.kast.cli.options.InstallOptions
import io.github.amichne.kast.cli.options.InstallSkillOptions
import io.github.amichne.kast.cli.options.RuntimeCommandOptions
import io.github.amichne.kast.cli.options.SmokeOptions
import io.github.amichne.kast.cli.skill.SkillWrapperName
import java.nio.file.Path

internal sealed interface CliCommand {
    interface BackendQuery<Q> : CliCommand {
        val options: RuntimeCommandOptions
        val query: Q
    }

    data class Help(val topic: List<String> = emptyList()) : CliCommand
    data object Version : CliCommand
    data object VerifyExtension : CliCommand
    data class Completion(val shell: CliCompletionShell) : CliCommand
    data class WorkspaceStatus(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceEnsure(val options: RuntimeCommandOptions) : CliCommand
    data class WorkspaceRefresh(override val options: RuntimeCommandOptions, override val query: RefreshQuery) : BackendQuery<RefreshQuery>
    data class WorkspaceStop(val options: RuntimeCommandOptions) : CliCommand
    data class Capabilities(val options: RuntimeCommandOptions) : CliCommand
    data class ResolveSymbol(override val options: RuntimeCommandOptions, override val query: SymbolQuery) : BackendQuery<SymbolQuery>
    data class FindReferences(override val options: RuntimeCommandOptions, override val query: ReferencesQuery) : BackendQuery<ReferencesQuery>
    data class CallHierarchy(override val options: RuntimeCommandOptions, override val query: CallHierarchyQuery) : BackendQuery<CallHierarchyQuery>
    data class TypeHierarchy(override val options: RuntimeCommandOptions, override val query: TypeHierarchyQuery) : BackendQuery<TypeHierarchyQuery>
    data class SemanticInsertionPoint(override val options: RuntimeCommandOptions, override val query: SemanticInsertionQuery) : BackendQuery<SemanticInsertionQuery>
    data class Diagnostics(override val options: RuntimeCommandOptions, override val query: DiagnosticsQuery) : BackendQuery<DiagnosticsQuery>
    data class FileOutline(override val options: RuntimeCommandOptions, override val query: FileOutlineQuery) : BackendQuery<FileOutlineQuery>
    data class WorkspaceSymbol(override val options: RuntimeCommandOptions, override val query: WorkspaceSymbolQuery) : BackendQuery<WorkspaceSymbolQuery>
    data class WorkspaceFiles(override val options: RuntimeCommandOptions, override val query: WorkspaceFilesQuery) : BackendQuery<WorkspaceFilesQuery>
    data class Implementations(override val options: RuntimeCommandOptions, override val query: ImplementationsQuery) : BackendQuery<ImplementationsQuery>
    data class CodeActions(override val options: RuntimeCommandOptions, override val query: CodeActionsQuery) : BackendQuery<CodeActionsQuery>
    data class Completions(override val options: RuntimeCommandOptions, override val query: CompletionsQuery) : BackendQuery<CompletionsQuery>
    data class Rename(override val options: RuntimeCommandOptions, override val query: RenameQuery) : BackendQuery<RenameQuery>
    data class ImportOptimize(override val options: RuntimeCommandOptions, override val query: ImportOptimizeQuery) : BackendQuery<ImportOptimizeQuery>
    data class ApplyEdits(override val options: RuntimeCommandOptions, override val query: ApplyEditsQuery) : BackendQuery<ApplyEditsQuery>
    data class Install(val options: InstallOptions) : CliCommand
    data class InstallSkill(val options: InstallSkillOptions) : CliCommand
    data class InstallCopilotExtension(val options: InstallCopilotExtensionOptions) : CliCommand
    data class Smoke(val options: SmokeOptions) : CliCommand
    data class DaemonStart(val options: DaemonStartOptions) : CliCommand
    data object ConfigInit : CliCommand
    data class Skill(val name: SkillWrapperName, val rawInput: String) : CliCommand
    data class EvalSkill(val options: EvalSkillOptions) : CliCommand
    data class GradleRun(
        val workspaceRoot: Path,
        val task: String,
        val extraArgs: List<String> = emptyList(),
    ) : CliCommand
    data class Metrics(
        val subcommand: MetricsSubcommand,
        val workspaceRoot: Path,
        val limit: Int = 50,
        val symbol: String? = null,
        val depth: Int = 3,
        val interactive: Boolean = false,
        val fileGlob: String? = null,
        val folderFilter: String? = null,
    ) : CliCommand
}

internal enum class MetricsSubcommand {
    FAN_IN,
    FAN_OUT,
    COUPLING,
    LOW_USAGE,
    CYCLES,
    MODULE_DEPTH,
    DEAD_CODE,
    IMPACT,
    GRAPH,
}

internal data class EvalSkillOptions(
    val skillDir: Path,
    val compareBaseline: Path? = null,
    val format: EvalOutputFormat = EvalOutputFormat.JSON,
)

internal enum class EvalOutputFormat { JSON, MARKDOWN }
