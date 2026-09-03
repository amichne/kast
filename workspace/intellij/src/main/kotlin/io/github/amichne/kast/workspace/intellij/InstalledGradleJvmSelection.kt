package io.github.amichne.kast.workspace.intellij

import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.ProjectJdkTable
import org.jetbrains.plugins.gradle.service.GradleInstallationManager
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Properties

internal enum class InstalledGradleJvmSelectionFailure {
    GRADLE_DISTRIBUTION_UNAVAILABLE,
    DAEMON_JVM_CRITERIA_UNSUPPORTED,
    REPOSITORY_JAVA_HOME_INVALID,
    LOCAL_JVM_DISCOVERY_FAILED,
    NO_COMPATIBLE_RUNTIME,
    SDK_REGISTRATION_FAILED,
}

internal sealed interface InstalledGradleJvmSelection {
    data class Selected(
        val jvm: SelectedGradleJvm,
    ) : InstalledGradleJvmSelection

    data class Rejected(
        val failure: InstalledGradleJvmSelectionFailure,
    ) : InstalledGradleJvmSelection
}

/**
 * IntelliJ effect boundary for deterministic Gradle JVM selection.
 *
 * The wrapper distribution is observed first. Repository-owned `org.gradle.java.home`, the
 * compatible sidecar, and IntelliJ's locally suggested/registered JDKs then become detached
 * candidates for [GradleJvmCandidateSelector]. No candidate is tried by launching Gradle.
 */
internal fun selectInstalledGradleJvm(
    project: Project,
    settings: GradleProjectSettings,
    sidecar: InstalledSidecarJvm,
): InstalledGradleJvmSelection {
    val distribution = try {
        GradleInstallationManager.guessGradleVersion(settings)
    } catch (_: RuntimeException) {
        return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    } ?: return InstalledGradleJvmSelection.Rejected(
        InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
    )

    val root = try {
        Path.of(settings.externalProjectPath).toRealPath()
    } catch (_: IOException) {
        return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    } catch (_: InvalidPathException) {
        return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    } catch (_: SecurityException) {
        return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    }

    val repositoryHome = when (val configured = repositoryGradleJavaHome(root)) {
        RepositoryGradleJavaHome.Absent -> null
        is RepositoryGradleJavaHome.Present -> configured.home
        RepositoryGradleJavaHome.Rejected -> return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.REPOSITORY_JAVA_HOME_INVALID,
        )
    }
    val repositoryCandidate = repositoryHome?.let { home ->
        observeGradleJvmCandidate(home, GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY)
            ?: return InstalledGradleJvmSelection.Rejected(
                InstalledGradleJvmSelectionFailure.REPOSITORY_JAVA_HOME_INVALID,
            )
    }
    val sidecarCandidate = observeGradleJvmCandidate(
        sidecar.home,
        GradleJvmSelectionSource.SIDECAR_COMPATIBLE,
    ) ?: return InstalledGradleJvmSelection.Rejected(
        InstalledGradleJvmSelectionFailure.LOCAL_JVM_DISCOVERY_FAILED,
    )

    val platformHomes: Set<String> = try {
        buildSet<String> {
            for (sdk in ProjectJdkTable.getInstance(project).allJdks) {
                val home = sdk.homePath
                if (home != null) add(home)
            }
            addAll(ExternalSystemJdkUtil.suggestJdkHomePaths(project))
        }
    } catch (_: RuntimeException) {
        return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.LOCAL_JVM_DISCOVERY_FAILED,
        )
    }
    val platformCandidates = platformHomes
        .sorted()
        .mapNotNull { raw ->
            val home = try {
                Path.of(raw)
            } catch (_: InvalidPathException) {
                return@mapNotNull null
            }
            observeGradleJvmCandidate(home, GradleJvmSelectionSource.PLATFORM_RESOLVER)
        }
    val candidates = buildList {
        if (repositoryCandidate != null) add(repositoryCandidate)
        add(sidecarCandidate)
        addAll(platformCandidates)
    }
    val selectionCandidates = when (
        val criteria = repositoryDaemonJvmCriteria(root, distribution)
    ) {
        RepositoryDaemonJvmCriteria.Absent -> candidates
        is RepositoryDaemonJvmCriteria.Required -> candidates
            .filter { candidate -> candidate.feature == criteria.feature }
            .map { candidate ->
                candidate.copy(source = GradleJvmSelectionSource.DAEMON_JVM_CRITERIA)
            }
        RepositoryDaemonJvmCriteria.Rejected -> return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.DAEMON_JVM_CRITERIA_UNSUPPORTED,
        )
    }
    val proof = when (
        val selection = GradleJvmCandidateSelector.select(distribution, selectionCandidates)
    ) {
        is GradleJvmCandidateSelection.Selected -> selection
        is GradleJvmCandidateSelection.Rejected -> return InstalledGradleJvmSelection.Rejected(
            InstalledGradleJvmSelectionFailure.NO_COMPATIBLE_RUNTIME,
        )
    }
    val selector = if (proof.candidate.source == GradleJvmSelectionSource.SIDECAR_COMPATIBLE) {
        ExternalSystemJdkUtil.USE_JAVA_HOME
    } else {
        try {
            val existing = ProjectJdkTable.getInstance(project).allJdks.firstOrNull { sdk ->
                sdk.homePath?.let { raw ->
                    runCatching { Path.of(raw).toRealPath() }.getOrNull() == proof.candidate.home
                } == true
            }
            (existing ?: ExternalSystemJdkUtil.addJdk(proof.candidate.home.toString())).name
        } catch (_: RuntimeException) {
            return InstalledGradleJvmSelection.Rejected(
                InstalledGradleJvmSelectionFailure.SDK_REGISTRATION_FAILED,
            )
        }
    }
    return InstalledGradleJvmSelection.Selected(
        SelectedGradleJvm.establish(proof, selector),
    )
}

internal sealed interface RepositoryDaemonJvmCriteria {
    data object Absent : RepositoryDaemonJvmCriteria

    data class Required(
        val feature: JavaFeature,
    ) : RepositoryDaemonJvmCriteria

    data object Rejected : RepositoryDaemonJvmCriteria
}

/**
 * Reads Gradle's checked-in daemon JVM criteria only for distributions that support it.
 *
 * The installed adapter can prove the generated version-only form. Vendor, native-image, or
 * future criteria fail closed until local JDK observation carries matching detached evidence.
 */
internal fun repositoryDaemonJvmCriteria(
    root: Path,
    distribution: org.gradle.util.GradleVersion,
): RepositoryDaemonJvmCriteria {
    if (distribution < DAEMON_JVM_CRITERIA_MINIMUM_GRADLE) {
        return RepositoryDaemonJvmCriteria.Absent
    }
    val criteriaFile = root.resolve("gradle/gradle-daemon-jvm.properties")
    if (Files.notExists(criteriaFile)) return RepositoryDaemonJvmCriteria.Absent
    if (
        Files.isSymbolicLink(criteriaFile) ||
        !Files.isRegularFile(criteriaFile) ||
        try {
            Files.size(criteriaFile) > MAX_DAEMON_JVM_CRITERIA_BYTES
        } catch (_: IOException) {
            true
        } catch (_: SecurityException) {
            true
        }
    ) {
        return RepositoryDaemonJvmCriteria.Rejected
    }
    val properties = Properties()
    try {
        Files.newBufferedReader(criteriaFile).use(properties::load)
    } catch (_: IOException) {
        return RepositoryDaemonJvmCriteria.Rejected
    } catch (_: SecurityException) {
        return RepositoryDaemonJvmCriteria.Rejected
    } catch (_: IllegalArgumentException) {
        return RepositoryDaemonJvmCriteria.Rejected
    }
    val unsupportedCriteria = properties.stringPropertyNames().any { name ->
        name.startsWith("toolchain") &&
            name != "toolchainVersion" &&
            !name.startsWith("toolchainUrl.")
    }
    if (unsupportedCriteria) return RepositoryDaemonJvmCriteria.Rejected
    val rawFeature = properties.getProperty("toolchainVersion")
        ?: return RepositoryDaemonJvmCriteria.Rejected
    val feature = rawFeature.toIntOrNull()
        ?.takeIf { value -> value > 0 }
        ?.let(JavaFeature::of)
        ?: return RepositoryDaemonJvmCriteria.Rejected
    return RepositoryDaemonJvmCriteria.Required(feature)
}

private fun observeGradleJvmCandidate(
    rawHome: Path,
    source: GradleJvmSelectionSource,
): GradleJvmCandidate? {
    val home = try {
        rawHome.toRealPath()
    } catch (_: IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    }
    if (!ExternalSystemJdkUtil.isValidJdk(home.toString())) return null
    val version = try {
        ExternalSystemJdkUtil.getJavaVersion(home.toString())
    } catch (_: RuntimeException) {
        null
    } ?: return null
    return GradleJvmCandidate(
        home = home,
        feature = JavaFeature.of(version.feature),
        runtimeVersion = version.toString(),
        source = source,
    )
}

private sealed interface RepositoryGradleJavaHome {
    data object Absent : RepositoryGradleJavaHome

    data class Present(
        val home: Path,
    ) : RepositoryGradleJavaHome

    data object Rejected : RepositoryGradleJavaHome
}

/** Reads only the repository-owned property; user and installation Gradle properties stay out. */
private fun repositoryGradleJavaHome(root: Path): RepositoryGradleJavaHome {
    val propertiesFile = root.resolve("gradle.properties")
    if (Files.notExists(propertiesFile)) return RepositoryGradleJavaHome.Absent
    if (!Files.isRegularFile(propertiesFile) || Files.isSymbolicLink(propertiesFile)) {
        return RepositoryGradleJavaHome.Rejected
    }
    val properties = Properties()
    try {
        Files.newBufferedReader(propertiesFile).use(properties::load)
    } catch (_: IOException) {
        return RepositoryGradleJavaHome.Rejected
    } catch (_: SecurityException) {
        return RepositoryGradleJavaHome.Rejected
    } catch (_: IllegalArgumentException) {
        return RepositoryGradleJavaHome.Rejected
    }
    val raw = properties.getProperty("org.gradle.java.home")
        ?.takeIf(String::isNotBlank)
        ?: return RepositoryGradleJavaHome.Absent
    val candidate = try {
        Path.of(raw)
    } catch (_: InvalidPathException) {
        return RepositoryGradleJavaHome.Rejected
    }
    if (!candidate.isAbsolute || candidate.normalize() != candidate) {
        return RepositoryGradleJavaHome.Rejected
    }
    val canonical = try {
        candidate.toRealPath()
    } catch (_: IOException) {
        return RepositoryGradleJavaHome.Rejected
    } catch (_: SecurityException) {
        return RepositoryGradleJavaHome.Rejected
    }
    return RepositoryGradleJavaHome.Present(canonical)
}

private val DAEMON_JVM_CRITERIA_MINIMUM_GRADLE =
    org.gradle.util.GradleVersion.version("8.8")
private const val MAX_DAEMON_JVM_CRITERIA_BYTES = 1_048_576L
