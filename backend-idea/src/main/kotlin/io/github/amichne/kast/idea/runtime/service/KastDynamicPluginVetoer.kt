package io.github.amichne.kast.idea

import com.intellij.ide.plugins.DynamicPluginVetoer
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.project.ProjectManager

internal const val KAST_PLUGIN_ID: String = "io.github.amichne.kast"
internal const val KAST_PLUGIN_DRAINING_UNLOAD_MESSAGE: String =
    "Kast is stopping its IDEA backend. Retry plugin unload after shutdown completes."

class KastDynamicPluginVetoer : DynamicPluginVetoer {
    override fun vetoPluginUnload(pluginDescriptor: IdeaPluginDescriptor): String? =
        dynamicPluginUnloadVetoReason(
            pluginId = pluginDescriptor.pluginId.idString,
            prepareServices = openKastServices().map { service ->
                service::prepareForDynamicUnload
            },
        )

    private fun openKastServices(): List<KastPluginService> =
        ProjectManager.getInstanceIfCreated()
            ?.openProjects
            .orEmpty()
            .mapNotNull { project ->
                project.getServiceIfCreated(KastPluginService::class.java)
            }
}

internal fun dynamicPluginUnloadVetoReason(
    pluginId: String,
    prepareServices: Iterable<() -> Boolean>,
): String? {
    if (pluginId != KAST_PLUGIN_ID) return null
    val readyToUnload = prepareServices.map { prepare -> prepare() }
    return if (readyToUnload.all { ready -> ready }) {
        null
    } else {
        KAST_PLUGIN_DRAINING_UNLOAD_MESSAGE
    }
}
