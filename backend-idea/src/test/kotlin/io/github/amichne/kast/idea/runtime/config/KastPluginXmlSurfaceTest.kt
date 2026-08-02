package io.github.amichne.kast.idea

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class KastPluginXmlSurfaceTest {
    @Test
    fun `public plugin has no foreground extension reachability`() {
        val pluginXml = Files.readString(Path.of("src/main/resources/META-INF/plugin.xml"))

        listOf(
            "projectService",
            "postStartupActivity",
            "statusBarWidgetFactory",
            "toolWindow",
            "projectConfigurable",
            "notificationGroup",
            "KastPluginService",
            "KastStartupActivity",
        ).forEach { forbidden ->
            assertFalse(pluginXml.contains(forbidden), forbidden)
        }
    }
}
