package support.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.util.zip.ZipFile

@CacheableTask
abstract class VerifyControlDistributionTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val controlDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val controlArchive: RegularFileProperty

    @get:Input abstract val maximumArchiveBytes: Property<Long>
    @get:Input abstract val maximumInstalledBytes: Property<Long>

    @TaskAction
    fun verify() {
        val root = controlDirectory.get().asFile.toPath()
        val archive = controlArchive.get().asFile
        check(archive.length() < maximumArchiveBytes.get()) {
            "control archive is ${archive.length()} bytes; limit is below 64 MiB"
        }
        val paths = Files.walk(root).use { stream -> stream.toList() }
        val entries = paths.map { path -> root.relativize(path).toString() }
        val installedBytes = paths
            .filter(Files::isRegularFile)
            .sumOf(Files::size)
        check(installedBytes < maximumInstalledBytes.get()) {
            "installed control product is $installedBytes bytes; limit is below 128 MiB"
        }
        val required = listOf(
            "bin/kast",
            "share/kast/operation-registry.json",
            "share/kast/wire-schema.json",
            "share/kast/semantic-runtime.json",
            "share/kast/licenses/LICENSE",
        )
        check(required.all(entries::contains)) { "control product is missing required entries" }
        val publicExecutables = Files.list(root.resolve("bin")).use { paths ->
            paths.filter(Files::isRegularFile).map { path -> path.fileName.toString() }.toList()
        }
        check(publicExecutables == listOf("kast")) {
            "control product must expose exactly one executable: $publicExecutables"
        }
        check(Files.isExecutable(root.resolve("bin/kast"))) {
            "control product kast launcher is not executable"
        }
        check(paths.none(Files::isSymbolicLink)) { "control product contains a symbolic link" }
        val forbidden = listOf("kast-indexer", "idea-home", "plugins/Kotlin", "plugins/gradle")
        check(entries.none { entry -> forbidden.any(entry::contains) }) {
            "control product contains semantic runtime payload"
        }
        val forbiddenJarEntries = listOf(
            "com/intellij/",
            "io/github/amichne/kast/indexer/",
            "org/jetbrains/kotlin/idea/",
        )
        val embeddedRuntimeEntry = paths
            .filter { path -> Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar") }
            .firstNotNullOfOrNull { jar ->
                ZipFile(jar.toFile()).use { archiveFile ->
                    archiveFile.entries().asSequence()
                        .map { entry -> entry.name }
                        .firstOrNull { entry -> forbiddenJarEntries.any(entry::startsWith) }
                        ?.let { entry -> "${root.relativize(jar)}!/$entry" }
                }
            }
        check(embeddedRuntimeEntry == null) {
            "control product embeds semantic runtime entry $embeddedRuntimeEntry"
        }
    }
}

@CacheableTask
abstract class VerifySemanticRuntimeDistributionTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val root = runtimeDirectory.get().asFile.toPath()
        check(Files.isExecutable(root.resolve("kast-indexer")))
        check(Files.isRegularFile(root.resolve("idea-home/product-info.json")))
        check(Files.isDirectory(root.resolve("idea-home/plugins/kast-indexer")))
        check(Files.isDirectory(root.resolve("runtime-libs")))
    }
}
