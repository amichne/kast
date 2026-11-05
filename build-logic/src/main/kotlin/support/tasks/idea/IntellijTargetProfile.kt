package support.tasks.idea

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

/** One source-declared SDK tuple; a supplied installation cannot silently retarget another module. */
class IntellijTargetProfile internal constructor(
    val intellijReleaseLine: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
)

enum class IntellijTargetSelectionFailure {
    INVALID_CONFIGURATION,
    UNSUPPORTED_SCHEMA,
    DUPLICATE_TARGET,
    TARGET_IDENTITY_MISMATCH,
    DEFAULT_TARGET_UNAVAILABLE,
    TARGET_UNAVAILABLE,
    SDK_BUILD_MISMATCH,
}

sealed interface IntellijTargetSelection {
    class Selected internal constructor(val profile: IntellijTargetProfile) : IntellijTargetSelection
    data class Rejected(val reason: IntellijTargetSelectionFailure) : IntellijTargetSelection
}

object IntellijTargetProfiles {
    fun select(encoded: String, requested: String?, suppliedBuilds: List<String> = emptyList()): IntellijTargetSelection {
        if (encoded.length > 65_536 || encoded.toByteArray().size > 65_536)
            return rejected(IntellijTargetSelectionFailure.INVALID_CONFIGURATION)
        val document = try {
            Json.decodeFromString<TargetConfiguration>(encoded)
        } catch (_: SerializationException) {
            return rejected(IntellijTargetSelectionFailure.INVALID_CONFIGURATION)
        }
        if (document.schemaVersion != 1) return rejected(IntellijTargetSelectionFailure.UNSUPPORTED_SCHEMA)
        if (document.targets.isEmpty() || document.targets.size > 8)
            return rejected(IntellijTargetSelectionFailure.INVALID_CONFIGURATION)
        if (document.targets.map { it.intellijReleaseLine }.distinct().size != document.targets.size)
            return rejected(IntellijTargetSelectionFailure.DUPLICATE_TARGET)
        if (document.targets.any {
                !it.intellijReleaseLine.matches(Regex("[0-9]{3}")) ||
                    !it.ideBuild.matches(Regex("${it.intellijReleaseLine}\\.[0-9]+\\.[0-9]+")) ||
                    !it.kotlinPluginBuild.matches(Regex("${it.intellijReleaseLine}\\.[0-9]+\\.[0-9]+-IJ"))
            }) return rejected(IntellijTargetSelectionFailure.TARGET_IDENTITY_MISMATCH)
        if (document.targets.none { it.intellijReleaseLine == document.defaultTarget })
            return rejected(IntellijTargetSelectionFailure.DEFAULT_TARGET_UNAVAILABLE)
        val selectedLine = requested ?: suppliedBuilds.firstOrNull()?.substringBefore('.') ?: document.defaultTarget
        val selected = document.targets.singleOrNull { it.intellijReleaseLine == selectedLine }
            ?: return rejected(IntellijTargetSelectionFailure.TARGET_UNAVAILABLE)
        if (suppliedBuilds.any { it != selected.ideBuild })
            return rejected(IntellijTargetSelectionFailure.SDK_BUILD_MISMATCH)
        return IntellijTargetSelection.Selected(
            IntellijTargetProfile(selected.intellijReleaseLine, selected.ideBuild, selected.kotlinPluginBuild)
        )
    }

    private fun rejected(reason: IntellijTargetSelectionFailure) = IntellijTargetSelection.Rejected(reason)
}

/** Configures only the caller's own model and retains an explicit producer for an extracted SDK. */
class SelectedIntellijTarget internal constructor(
    val profile: IntellijTargetProfile,
    val suppliedHome: File?,
    val scopedOutputs: Boolean,
) {
    fun configureModule(project: Project) {
        configureBuildDirectory(project)
        if (profile.intellijReleaseLine == "263") project.tasks.withType(KotlinCompile::class.java).configureEach {
            if (name == "compileTestKotlin" || name == "compileNativeTestKotlin" || name == "compileTestFixturesKotlin")
                compilerOptions.optIn.add("org.jetbrains.kotlin.CoreEnvironmentDeprecation")
        }
    }

    fun configureBuildDirectory(project: Project) {
        if (scopedOutputs)
            project.layout.buildDirectory.set(project.layout.projectDirectory.dir("build/ide-targets/${profile.intellijReleaseLine}"))
    }

    fun sdkHome(project: Project): Provider<Directory> {
        return suppliedHome?.let { home -> project.layout.dir(project.providers.provider { home }) }
            ?: extractedSdkHome(project)
    }

    /** A producer can write only its owned cache, never a supplied vendor installation. */
    fun extractedSdkHome(project: Project): Provider<Directory> {
        val home = project.gradle.gradleUserHomeDir.resolve("kast/intellij-targets/${profile.ideBuild}")
        return project.layout.dir(project.providers.provider { home })
    }

    fun nativeFixtureHome(project: Project): Provider<Directory> {
        val home = suppliedHome ?: project.isolated.rootProject.projectDirectory.asFile.resolve(
            if (scopedOutputs) "relation/intellij/build/ide-targets/${profile.intellijReleaseLine}/native-test/idea"
            else "relation/intellij/build/native-test/idea"
        )
        return project.layout.dir(project.providers.provider { home })
    }

    fun libraries(project: Project, vararg families: IntellijSdkLibraryFamily): ConfigurableFileCollection {
        val tree = project.fileTree(sdkHome(project)) {
            families.forEach { family -> include(family.pattern) }
            exclude("plugins/Kotlin/lib/jps/**", "plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
            exclude("lib/intellij.libraries.kotlinx.serialization.*.jar", "lib/intellij.libraries.ktor.utils.jar")
        }
        return project.files(tree).apply {
            if (suppliedHome == null) builtBy(":workspace:intellij-read:extractWorkspaceReadIdeaDistribution")
        }
    }
}

enum class IntellijSdkLibraryFamily(val pattern: String) {
    PLATFORM("lib/**/*.jar"),
    KOTLIN("plugins/Kotlin/lib/**/*.jar"),
    JAVA("plugins/java/lib/**/*.jar"),
    GRADLE("plugins/gradle*/lib/**/*.jar"),
}

/** Common Control metadata is independent of a Host build's selected target or supplied SDK. */
fun defaultIntellijTargetProfile(project: Project): IntellijTargetProfile = with(project) {
    val encoded = providers.fileContents(isolated.rootProject.projectDirectory.file("config/intellij-targets.json")).asText.get()
    when (val selection = IntellijTargetProfiles.select(encoded, null)) {
        is IntellijTargetSelection.Selected -> selection.profile
        is IntellijTargetSelection.Rejected -> throw GradleException("IntelliJ target configuration rejected: ${selection.reason}")
    }
}

fun selectedIntellijTarget(project: Project): SelectedIntellijTarget = with(project) {
    val root = isolated.rootProject.projectDirectory
    val encoded = providers.fileContents(root.file("config/intellij-targets.json")).asText.get()
    val homes = listOf("hostedIdeaHome", "nativeFixtureIdeaHome").mapNotNull { name ->
        providers.gradleProperty(name).orNull?.let { raw ->
            if (!File(raw).isAbsolute)
                throw GradleException("Selected IDEA SDK must be a physical absolute directory")
            val home = file(raw)
            if (!home.isAbsolute || home.canonicalFile != home)
                throw GradleException("Selected IDEA SDK must be a physical absolute directory")
            home
        }
    }
    val builds = homes.map { home ->
        val metadata = providers.fileContents(layout.projectDirectory.file(selectedIdeaProductInfo(home).absolutePath)).asText.get()
        try {
            Json { ignoreUnknownKeys = true }.decodeFromString<SelectedTargetProductInfo>(metadata).buildNumber
        } catch (_: SerializationException) {
            throw GradleException("Selected IDEA SDK has invalid vendor metadata")
        }
    }
    val requested = providers.gradleProperty("intellijTarget").orNull
    when (val selection = IntellijTargetProfiles.select(encoded, requested, builds)) {
        is IntellijTargetSelection.Rejected -> throw GradleException("IntelliJ target selection rejected: ${selection.reason}")
        is IntellijTargetSelection.Selected -> SelectedIntellijTarget(
            selection.profile, homes.firstOrNull(), requested != null || homes.isNotEmpty(),
        )
    }
}

@Serializable
private data class TargetConfiguration(
    val schemaVersion: Int,
    val defaultTarget: String,
    val targets: List<TargetProfileDocument>,
)

@Serializable
private data class TargetProfileDocument(
    val intellijReleaseLine: String,
    val ideBuild: String,
    val kotlinPluginBuild: String,
)

@Serializable
private data class SelectedTargetProductInfo(val buildNumber: String)
