import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.transform.CacheableTransform
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** One plugin or platform library family of an IntelliJ IDEA distribution archive. */
enum class IdeaLibraryFamily {
    PLATFORM,
    KOTLIN,
    JAVA,
    GRADLE;

    internal val artifactType: String = "kast-idea-${name.lowercase()}-libraries"

    internal fun includes(entryName: String): Boolean {
        if (!entryName.endsWith(".jar")) return false
        val segments = entryName.split('/')
        val pluginIndex = segments.indexOf("plugins")
        val pluginLibrary = pluginIndex >= 0 && pluginIndex + 3 < segments.size && segments[pluginIndex + 2] == "lib"
        return when (this) {
            PLATFORM -> pluginIndex < 0 && "lib" in segments
            KOTLIN -> pluginLibrary && segments[pluginIndex + 1] == "Kotlin"
            JAVA -> pluginLibrary && segments[pluginIndex + 1] == "java"
            GRADLE -> pluginLibrary && segments[pluginIndex + 1].startsWith("gradle")
        }
    }

    /**
     * Compile classpaths omit the JPS build plugin, the bundled compiler, and platform copies of kotlinx.serialization and
     * ktor utilities, which would shadow the repository's own versions.
     */
    internal fun compiles(entryName: String): Boolean {
        if (!includes(entryName)) return false
        val segments = entryName.split('/')
        val name = segments.last()
        val parent = segments.getOrNull(segments.size - 2)
        return when (this) {
            PLATFORM ->
                parent != "lib" ||
                    !(name.startsWith("intellij.libraries.kotlinx.serialization.") ||
                        name == "intellij.libraries.ktor.utils.jar")
            KOTLIN -> {
                val library = segments.subList(segments.indexOf("plugins") + 3, segments.size)
                library.first() != "jps" && library != listOf("kotlinc", "lib", "kotlin-compiler.jar")
            }
            JAVA,
            GRADLE -> true
        }
    }
}

/**
 * Unpacks one library family from an IDEA distribution archive while its consumer configuration resolves. IDE Gradle
 * sync resolves configurations without running tasks, so jars produced here, unlike task outputs, are present in the
 * imported project model.
 */
@CacheableTransform
abstract class ExtractIdeaLibrariesTransform : TransformAction<ExtractIdeaLibrariesTransform.Parameters> {
    interface Parameters : TransformParameters {
        @get:Input val family: Property<IdeaLibraryFamily>
    }

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        val family = parameters.family.get()
        val archiveFile = archive.get().asFile
        var extracted = 0
        ZipFile(archiveFile).use { zip ->
            for (entry in zip.entries().asSequence().filter { !it.isDirectory && family.compiles(it.name) }) {
                val relative = Path.of(entry.name).normalize()
                if (relative.isAbsolute || relative.startsWith("..")) {
                    throw GradleException("Unsafe IDEA archive entry ${entry.name} in $archiveFile.")
                }
                val target = outputs.file("${family.name.lowercase()}/$relative").toPath()
                Files.createDirectories(target.parent)
                zip.getInputStream(entry).use { input -> Files.copy(input, target) }
                extracted++
            }
        }
        if (extracted == 0) throw GradleException("IDEA archive $archiveFile has no $family libraries.")
    }
}

private const val TRANSFORMS_REGISTERED = "kast.ideaLibraryTransformsRegistered"

/** The [family] jars of the single IDEA archive in [distribution], available whenever the configuration resolves. */
fun Project.ideaLibraries(distribution: Configuration, family: IdeaLibraryFamily): FileCollection {
    val extra = extensions.extraProperties
    if (!extra.has(TRANSFORMS_REGISTERED)) {
        extra.set(TRANSFORMS_REGISTERED, true)
        IdeaLibraryFamily.entries.forEach { registered ->
            dependencies.registerTransform(ExtractIdeaLibrariesTransform::class.java) {
                from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "zip")
                to.attribute(ARTIFACT_TYPE_ATTRIBUTE, registered.artifactType)
                parameters.family.set(registered)
            }
        }
    }
    return distribution.incoming
        .artifactView { attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, family.artifactType) }
        .files
}
