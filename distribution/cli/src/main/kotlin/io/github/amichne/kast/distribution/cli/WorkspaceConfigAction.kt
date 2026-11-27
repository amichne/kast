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
import io.github.amichne.kast.distribution.contract.configuration.HostHeapPolicy
import io.github.amichne.kast.distribution.contract.configuration.HostIdleShutdown
import io.github.amichne.kast.distribution.contract.configuration.KastConfigurationCatalogue
import io.github.amichne.kast.distribution.contract.configuration.WorkspaceDependencySharing
import io.github.amichne.kast.kernel.Refinement

internal sealed interface WorkspaceConfigAction {
    data object Show : WorkspaceConfigAction

    class Explain(val parameter: ConfigurationParameter) : WorkspaceConfigAction

    sealed interface Edit : WorkspaceConfigAction {
        val setting: EditableWorkspaceSetting
    }

    class Set(val assignment: EditableAssignment) : Edit {
        override val setting: EditableWorkspaceSetting
            get() = assignment.setting
    }

    class Unset(override val setting: EditableWorkspaceSetting) : Edit
}

/** The workspace settings `kast config set` writes. Each value passes its owner's parser before it is saved. */
internal enum class EditableWorkspaceSetting(val parameter: ConfigurationParameter, val accepted: String) {
    WORKSPACE_DEPENDENCY_SHARING(ConfigurationParameter.WORKSPACE_DEPENDENCY_SHARING, "PRIVATE or SHARED"),
    HOST_MAX_HEAP_MIB(ConfigurationParameter.HOST_MAX_HEAP_MIB, "whole MiB from 1024 to 32768"),
    HOST_IDLE_SHUTDOWN(ConfigurationParameter.HOST_IDLE_SHUTDOWN, "off, or whole minutes from 5 to 1440");

    val key: String
        get() = parameter.key

    fun admit(raw: String): Refinement<EditableAssignment, EditableSettingFailure> {
        val saved =
            when (this) {
                WORKSPACE_DEPENDENCY_SHARING ->
                    when (val parsed = WorkspaceDependencySharing.parseSetting(raw)) {
                        is Refinement.Refined -> parsed.value.name
                        is Refinement.Rejected -> null
                    }
                HOST_MAX_HEAP_MIB ->
                    when (val parsed = HostHeapPolicy.Explicit.parseSetting(raw)) {
                        is Refinement.Refined -> parsed.value.heap.mebibytes.toString()
                        is Refinement.Rejected -> null
                    }
                HOST_IDLE_SHUTDOWN ->
                    when (val parsed = HostIdleShutdown.parseSetting(raw)) {
                        is Refinement.Refined ->
                            when (val policy = parsed.value) {
                                HostIdleShutdown.Disabled -> HostIdleShutdown.DISABLED_SETTING
                                is HostIdleShutdown.After -> policy.minutes.toString()
                            }
                        is Refinement.Rejected -> null
                    }
            }
        return if (saved == null) Refinement.Rejected(EditableSettingFailure.VALUE_REJECTED)
        else Refinement.Refined(EditableAssignment(this, saved))
    }

    companion object {
        fun of(key: String): EditableWorkspaceSetting? = entries.singleOrNull { it.key == key }

        val keys: String
            get() = entries.joinToString(", ") { it.key }
    }
}

internal enum class EditableSettingFailure {
    VALUE_REJECTED
}

/** An owner-admitted value in the exact form the saved workspace file records. */
internal class EditableAssignment internal constructor(val setting: EditableWorkspaceSetting, val value: String) {
    val line: String
        get() = "${setting.key}=$value"
}

internal fun workspaceConfigCommand(): ManagementNode =
    object : ManagementNode("config") {
            override fun selection(): ManagementCommand? = null

            override fun help(context: Context) =
                "Inspect, explain, and edit the saved settings of one workspace. `explain <key>` describes a " +
                    "setting, its accepted values, its effective value, and how to apply a change. " +
                    "`set` and `unset` edit ${EditableWorkspaceSetting.keys}."
        }
        .subcommands(Show(), Explain(), SetValue(), Unset())

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
    private val key by argument("key", help = "Any setting from `kast config show` or the configuration reference.")

    override fun action() =
        WorkspaceConfigAction.Explain(KastConfigurationCatalogue.parameter(key) ?: throw CliktError("UNKNOWN_KEY"))
}

private abstract class EditNode(name: String) : WorkspaceConfigNode(name) {
    private val key by argument("key", help = "One of ${EditableWorkspaceSetting.keys}.")

    protected abstract fun edit(setting: EditableWorkspaceSetting): WorkspaceConfigAction.Edit

    override fun action(): WorkspaceConfigAction.Edit =
        edit(
            EditableWorkspaceSetting.of(key)
                ?: throw CliktError("$key is not a saved workspace setting. Edit ${EditableWorkspaceSetting.keys}.")
        )
}

private class SetValue : EditNode("set") {
    private val value by
        argument("value", help = EditableWorkspaceSetting.entries.joinToString("; ") { "${it.key}: ${it.accepted}" })

    override fun edit(setting: EditableWorkspaceSetting) =
        when (val admitted = setting.admit(value)) {
            is Refinement.Refined -> WorkspaceConfigAction.Set(admitted.value)
            is Refinement.Rejected -> throw CliktError("${setting.key} accepts ${setting.accepted}.")
        }
}

private class Unset : EditNode("unset") {
    override fun edit(setting: EditableWorkspaceSetting) = WorkspaceConfigAction.Unset(setting)
}
