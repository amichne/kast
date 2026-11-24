package support.tasks.nativefixtures

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask

/**
 * The address alone is not buildable: consumers depend on [contents] so the SDK producer, or the admission of a
 * supplied home, runs before any consumer reads the distribution.
 */
class NativeFixtureIdea internal constructor(
    val home: Provider<Directory>,
    val contents: FileCollection,
    private val providers: ProviderFactory,
    private val objects: ObjectFactory,
) {
    /**
     * The distribution's jars in path order. A directory listing's order belongs to its file system, so two machines
     * would give one classpath two orders, and an ordered classpath is part of a task's cache key. The listing waits for
     * [contents]; an IDE synchronization that precedes it sees no jars.
     */
    fun jars(includes: List<String>, excludes: List<String> = emptyList()): FileCollection {
        val listing =
            providers.of(SortedJars::class.java) {
                parameters.home.set(home.map { it.asFile.absolutePath })
                parameters.includes.set(includes)
                parameters.excludes.set(excludes)
            }
        return objects.fileCollection().from(listing).builtBy(contents)
    }
}

/** Lists the regular files that one Ant-style pattern includes and none excludes. */
abstract class SortedJars : ValueSource<List<String>, SortedJars.Parameters> {
    interface Parameters : ValueSourceParameters {
        val home: Property<String>
        val includes: ListProperty<String>
        val excludes: ListProperty<String>
    }

    override fun obtain(): List<String> {
        val home = Path.of(parameters.home.get())
        if (!Files.isDirectory(home)) return emptyList()
        val includes = parameters.includes.get().map(::antPattern)
        val excludes = parameters.excludes.get().map(::antPattern)
        return Files.walk(home)
            .use { paths -> paths.filter(Files::isRegularFile).map { home.relativize(it).joinToString("/") }.toList() }
            .filter { name -> includes.any { it.matches(name) } && excludes.none { it.matches(name) } }
            .sorted()
            .map { home.resolve(it).toString() }
    }
}

/** Two stars match any number of directories, also none. One star stays within one name. */
internal fun antPattern(pattern: String): Regex =
    Regex(
        Regex("""\*\*/|\*\*|\*|[^*]+""").findAll(pattern).joinToString("") {
            when (it.value) {
                "**/" -> "(?:.*/)?"
                "**" -> ".*"
                "*" -> "[^/]*"
                else -> Regex.escape(it.value)
            }
        }
    )

/** The one owner of the native-test IDEA home: the extracted pinned distribution, or an admitted override. */
object SharedNativeFixtureIdea {
    const val PROPERTY = "nativeFixtureIdeaHome"
    const val DEFAULT_PRODUCER = ":relation:intellij:extractNativeTestIdea"
    const val ADMISSION_TASK = "admitNativeFixtureIdea"

    fun select(project: Project, pinnedBuild: String): NativeFixtureIdea = with(project) {
        val consumerDirectory = layout.projectDirectory
        val defaultHome = isolated.rootProject.projectDirectory.dir("relation/intellij/build/native-test/idea")
        val suppliedHome = providers.gradleProperty(PROPERTY)
        val home = suppliedHome.map(consumerDirectory::dir).orElse(providers.provider { defaultHome })
        val contents = files(home)
        if (suppliedHome.isPresent) {
            // Isolated projects forbid cross-project registration, so each consumer admits the override itself.
            val admission = tasks.register(ADMISSION_TASK, AdmitNativeFixtureIdea::class.java) {
                suppliedValue.set(suppliedHome)
                resolvedHome.set(home.map { it.asFile.absolutePath })
                this.pinnedBuild.set(pinnedBuild)
            }
            contents.builtBy(admission)
        } else {
            contents.builtBy(DEFAULT_PRODUCER)
        }
        NativeFixtureIdea(home, contents, providers, objects)
    }
}

/** Fixed product metadata projection: admission interprets only the declared build. */
@Serializable
internal data class NativeFixtureIdeaBuild(val buildNumber: String)

internal sealed interface NativeFixtureIdeaObservation {
    data object MissingDirectory : NativeFixtureIdeaObservation

    data object MissingProductInfo : NativeFixtureIdeaObservation

    data object UnreadableProductInfo : NativeFixtureIdeaObservation

    data class ProductInfo(val encoded: String) : NativeFixtureIdeaObservation
}

internal sealed interface NativeFixtureIdeaRejection {
    data object MissingDirectory : NativeFixtureIdeaRejection

    data object MissingProductInfo : NativeFixtureIdeaRejection

    data object UnreadableProductInfo : NativeFixtureIdeaRejection

    data object InvalidProductInfo : NativeFixtureIdeaRejection

    data class BuildMismatch(val observed: String) : NativeFixtureIdeaRejection
}

internal sealed interface NativeFixtureIdeaAdmission {
    data object Admitted : NativeFixtureIdeaAdmission

    data class Rejected(val rejection: NativeFixtureIdeaRejection) : NativeFixtureIdeaAdmission
}

internal object NativeFixtureIdeaAdmissionPolicy {
    // Product metadata has other fixed fields owned by JetBrains; admission never interprets them.
    private val json = Json { ignoreUnknownKeys = true }

    fun observe(home: Path): NativeFixtureIdeaObservation {
        if (!Files.isDirectory(home)) return NativeFixtureIdeaObservation.MissingDirectory
        val productInfo = home.resolve("product-info.json")
        if (!Files.isRegularFile(productInfo)) return NativeFixtureIdeaObservation.MissingProductInfo
        return try {
            NativeFixtureIdeaObservation.ProductInfo(Files.readString(productInfo))
        } catch (_: IOException) {
            NativeFixtureIdeaObservation.UnreadableProductInfo
        }
    }

    fun decide(observation: NativeFixtureIdeaObservation, pinnedBuild: String): NativeFixtureIdeaAdmission {
        val rejection =
            when (observation) {
                NativeFixtureIdeaObservation.MissingDirectory -> NativeFixtureIdeaRejection.MissingDirectory
                NativeFixtureIdeaObservation.MissingProductInfo -> NativeFixtureIdeaRejection.MissingProductInfo
                NativeFixtureIdeaObservation.UnreadableProductInfo -> NativeFixtureIdeaRejection.UnreadableProductInfo
                is NativeFixtureIdeaObservation.ProductInfo -> {
                    val build =
                        try {
                            json.decodeFromString<NativeFixtureIdeaBuild>(observation.encoded).buildNumber
                        } catch (_: SerializationException) {
                            return NativeFixtureIdeaAdmission.Rejected(NativeFixtureIdeaRejection.InvalidProductInfo)
                        } catch (_: IllegalArgumentException) {
                            return NativeFixtureIdeaAdmission.Rejected(NativeFixtureIdeaRejection.InvalidProductInfo)
                        }
                    if (build == pinnedBuild) return NativeFixtureIdeaAdmission.Admitted
                    NativeFixtureIdeaRejection.BuildMismatch(build)
                }
            }
        return NativeFixtureIdeaAdmission.Rejected(rejection)
    }

    fun message(
        property: String,
        suppliedValue: String,
        resolvedHome: String,
        pinnedBuild: String,
        rejection: NativeFixtureIdeaRejection,
    ): String {
        val problem =
            when (rejection) {
                NativeFixtureIdeaRejection.MissingDirectory -> "the directory does not exist"
                NativeFixtureIdeaRejection.MissingProductInfo -> "the directory has no product-info.json"
                NativeFixtureIdeaRejection.UnreadableProductInfo -> "its product-info.json cannot be read"
                NativeFixtureIdeaRejection.InvalidProductInfo -> "its product-info.json has no valid buildNumber"
                is NativeFixtureIdeaRejection.BuildMismatch ->
                    "it is build ${rejection.observed} instead of pinned build $pinnedBuild"
            }
        val location = if (suppliedValue == resolvedHome) resolvedHome else "$suppliedValue (resolved to $resolvedHome)"
        return "Native fixture IDEA home rejected: Gradle property $property points at $location, but $problem. " +
            "Remove the $property override to use the extracted pinned distribution, " +
            "or point it at an IntelliJ IDEA $pinnedBuild distribution."
    }
}

/** Admits a supplied native-test IDEA home before any consumer reads it. */
@UntrackedTask(because = "Admission re-observes an external distribution on every build.")
abstract class AdmitNativeFixtureIdea : DefaultTask() {
    @get:Input abstract val suppliedValue: Property<String>

    @get:Input abstract val resolvedHome: Property<String>

    @get:Input abstract val pinnedBuild: Property<String>

    @TaskAction
    fun admit() {
        val home = resolvedHome.get()
        val pinned = pinnedBuild.get()
        val observation = NativeFixtureIdeaAdmissionPolicy.observe(Path.of(home))
        when (val decision = NativeFixtureIdeaAdmissionPolicy.decide(observation, pinned)) {
            NativeFixtureIdeaAdmission.Admitted -> Unit
            is NativeFixtureIdeaAdmission.Rejected ->
                throw GradleException(
                    NativeFixtureIdeaAdmissionPolicy.message(
                        SharedNativeFixtureIdea.PROPERTY, suppliedValue.get(), home, pinned, decision.rejection,
                    ),
                )
        }
    }
}
