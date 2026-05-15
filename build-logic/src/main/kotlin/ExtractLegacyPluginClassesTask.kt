import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

@CacheableTask
abstract class ExtractLegacyPluginClassesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ideaDistributionDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {
        val distributionRoot = ideaDistributionDirectory.get().asFile
        val compilerJar = distributionRoot.walkTopDown()
                              .firstOrNull { file ->
                                  file.isFile && file.name == "kotlin-compiler.jar" &&
                                  file.invariantSeparatorsPath.contains("/plugins/Kotlin/kotlinc/lib/")
                              }
                          ?: throw GradleException(
                              "IntelliJ IDEA distribution under $distributionRoot did not contain plugins/Kotlin/kotlinc/lib/kotlin-compiler.jar.",
                          )

        val excludedEntries = setOf(
            "com/intellij/ide/plugins/ContainerDescriptor.class",
            "com/intellij/ide/plugins/IdeaPluginDescriptorImpl.class",
            "com/intellij/ide/plugins/IdeaPluginDescriptorImplKt.class",
            "com/intellij/ide/plugins/PluginDescriptorLoader.class",
            $$"com/intellij/ide/plugins/PluginDescriptorLoader$loadForCoreEnv$1.class",
            "com/intellij/ide/plugins/DataLoader.class",
            "com/intellij/ide/plugins/ImmutableZipFileDataLoader.class",
            "com/intellij/ide/plugins/NonShareableJavaZipFilePool.class",
        )
        val excludedPrefixes = listOf(
            "com/intellij/ide/plugins/ImmutableZipFileDataLoader$",
            "com/intellij/ide/plugins/NonShareableJavaZipFilePool$",
        )
        val outputRoot = outputDirectory.get().asFile
        outputRoot.deleteRecursively()
        outputRoot.mkdirs()

        ZipFile(compilerJar).use { archive ->
            val entries = archive.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    continue
                }

                val name = entry.name
                val included =
                    name.startsWith("com/intellij/ide/plugins/") && name.endsWith(".class") ||
                    name == "com/intellij/util/messages/ListenerDescriptor.class"
                val excluded = name in excludedEntries || excludedPrefixes.any(name::startsWith)
                if (!included || excluded) {
                    continue
                }

                val target = outputRoot.resolve(name)
                target.parentFile.mkdirs()
                archive.getInputStream(entry).use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }
}
