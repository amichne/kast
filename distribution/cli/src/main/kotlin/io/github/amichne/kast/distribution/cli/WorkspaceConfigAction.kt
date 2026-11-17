package io.github.amichne.kast.distribution.cli

import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import io.github.amichne.kast.distribution.contract.configuration.ConfigurationParameter
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.kernel.Refinement

internal sealed interface WorkspaceConfigAction {
    data object Show : WorkspaceConfigAction

    class Explain(val parameter: ConfigurationParameter) : WorkspaceConfigAction

    sealed interface Edit : WorkspaceConfigAction

    class Set(val policy: WorkspaceDependencySharing) : Edit

    data object Unset : Edit
}

internal fun workspaceConfigCommand(): ManagementNode =
    object : ManagementNode("config") {
            override fun selection(): ManagementCommand? = null

            override fun help(context: Context) =
                "Inspect saved settings or edit the dependency policy for one workspace."
        }
        .subcommands(Show(), Explain(), SetPolicy(), Unset())

private abstract class WorkspaceConfigNode(name: String) : ManagementNode(name) {
    private val workspace by option("--workspace", help = "Select the Gradle workspace.").path().required()
    private val json by option("--json", help = "Print the workspace file, effective value, and source.").flag()

    protected abstract fun action(): WorkspaceConfigAction

    override fun selection() = ManagementCommand.Config(workspace, action(), json)
}

private class Show : WorkspaceConfigNode("show") {
    override fun action() = WorkspaceConfigAction.Show
}

private class Explain : WorkspaceConfigNode("explain") {
    private val key by argument("key")

    override fun action() =
        WorkspaceConfigAction.Explain(KastConfigurationCatalogue.parameter(key) ?: throw CliktError("UNKNOWN_KEY"))
}

private abstract class EditNode(name: String) : WorkspaceConfigNode(name) {
    private val key by argument("key", help = "Use KAST_WORKSPACE_DEPENDENCY_SHARING.")

    protected abstract fun edit(): WorkspaceConfigAction.Edit

    override fun action(): WorkspaceConfigAction.Edit {
        if (key != ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING.key)
            throw CliktError("Only KAST_WORKSPACE_DEPENDENCY_SHARING can be edited.")
        return edit()
    }
}

private class SetPolicy : EditNode("set") {
    private val value by argument("value", help = "PRIVATE or SHARED")

    override fun edit() =
        when (val parsed = WorkspaceDependencySharing.parseSetting(value)) {
            is Refinement.Refined -> WorkspaceConfigAction.Set(parsed.value)
            is Refinement.Rejected -> throw CliktError("KAST_WORKSPACE_DEPENDENCY_SHARING accepts PRIVATE or SHARED.")
        }
}

private class Unset : EditNode("unset") {
    override fun edit() = WorkspaceConfigAction.Unset
}
