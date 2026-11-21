package io.github.amichne.kast.runtime.hosted.lifecycle

import com.intellij.ide.plugins.CannotUnloadPluginException
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.application.ApplicationManager

/** Plugin unload waits for Host settlement; managed headless startup belongs to the native opener. */
class IdeLifecycleStartup : DynamicPluginListener {
    override fun checkUnloadPlugin(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        if (!ApplicationManager.getApplication().getService(IdeLifecycleApplication::class.java).canUnloadHost()) {
            throw CannotUnloadPluginException("Kast Host settlement must complete before plugin reload")
        }
    }

    private companion object {
        const val PLUGIN_ID = "io.github.amichne.kast.ide-hosted"
    }
}
