package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KastPluginXmlSurfaceTest {
    @Test
    fun `plugin surface stays diagnostic only`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        assertTrue(pluginXml.contains("KastSettingsConfigurable"))
        assertTrue(pluginXml.contains("KastDiagnosticsService"))
        assertTrue(pluginXml.contains("KastStatusBarWidgetFactory"))
        assertTrue(pluginXml.contains("KastToolWindowFactory"))
        assertTrue(pluginXml.contains("""<toolWindow id="Kast""""))
        assertTrue(pluginXml.contains("""<notificationGroup id="Kast Activity" displayType="TOOL_WINDOW""""))
        assertFalse(pluginXml.contains("InstallSkillAction"))
        assertFalse(pluginXml.contains("InstallCopilotExtensionAction"))
        assertFalse(pluginXml.contains("UninstallCopilotExtensionAction"))
        assertFalse(pluginXml.contains("Install Kast Skill"))
        assertFalse(pluginXml.contains("Install Copilot Extension"))
        assertFalse(pluginXml.contains("Uninstall Copilot Extension"))
    }
}
