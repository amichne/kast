import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

internal fun selectPluginZip(distributionsDirectory: File): File =
    distributionsDirectory.listFiles()?.firstOrNull { it.name.endsWith(".zip") }
        ?: error("No plugin zip found in $distributionsDirectory")

abstract class VerifyPluginXmlPresentTask : DefaultTask() {
    init {
        forbiddenBundledJarPrefixes.convention(emptyList())
    }

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val distributionsDirectory: DirectoryProperty

    @get:Input
    abstract val expectedPluginId: Property<String>

    @get:Input
    abstract val rejectedPluginId: Property<String>

    @get:Input
    abstract val forbiddenBundledJarPrefixes: ListProperty<String>

    @TaskAction
    fun verify() {
        val distDir = distributionsDirectory.get().asFile
        val pluginZip = selectPluginZip(distDir)

        val content = ZipFile(pluginZip).use { zipFile ->
            val forbiddenBundledJars = zipFile.entries().asSequence()
                .filter { entry -> !entry.isDirectory && entry.name.endsWith(".jar") }
                .map { entry -> entry.name.substringAfterLast('/') }
                .filter { jarName ->
                    forbiddenBundledJarPrefixes.get().any(jarName::startsWith)
                }
                .sorted()
                .toList()
            check(forbiddenBundledJars.isEmpty()) {
                "Plugin ZIP must not bundle IDEA platform-provided runtime JARs: " +
                    forbiddenBundledJars.joinToString()
            }

            zipFile.entries().asSequence()
                .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
                ?.let { entry -> zipFile.getInputStream(entry).bufferedReader().use { reader -> reader.readText() } }
                ?: zipFile.entries().asSequence()
                    .filter { entry -> !entry.isDirectory && entry.name.endsWith(".jar") }
                    .mapNotNull { jarEntry ->
                        JarInputStream(zipFile.getInputStream(jarEntry)).use { jarStream ->
                            generateSequence { jarStream.nextJarEntry }
                                .firstOrNull { entry -> !entry.isDirectory && entry.name == "META-INF/plugin.xml" }
                                ?.let {
                                    jarStream.bufferedReader().use { reader -> reader.readText() }
                                }
                        }
                    }
                    .firstOrNull()
                ?: error("plugin.xml not found in ${pluginZip.name}")
        }

        val expectedIdTag = "<id>${expectedPluginId.get()}</id>"
        val rejectedIdTag = "<id>${rejectedPluginId.get()}</id>"
        check("KastPluginService" in content) { "plugin.xml is missing KastPluginService extension" }
        check("KastStartupActivity" in content) { "plugin.xml is missing KastStartupActivity extension" }
        check("KastSettingsConfigurable" in content) { "plugin.xml is missing KastSettingsConfigurable" }
        check("KastSettingsState" in content) { "plugin.xml is missing KastSettingsState" }
        check("InstallSkillAction" !in content) { "plugin.xml must not expose InstallSkillAction" }
        check("InstallCopilotExtensionAction" !in content) { "plugin.xml must not expose InstallCopilotExtensionAction" }
        check("UninstallCopilotExtensionAction" !in content) { "plugin.xml must not expose UninstallCopilotExtensionAction" }
        check("org.jetbrains.kotlin" in content) { "plugin.xml is missing Kotlin plugin dependency" }
        check(expectedIdTag in content) {
            "plugin.xml must keep production plugin ID ${expectedPluginId.get()}"
        }
        check(rejectedIdTag !in content) {
            "plugin.xml contains rejected plugin ID ${rejectedPluginId.get()}"
        }
    }
}
