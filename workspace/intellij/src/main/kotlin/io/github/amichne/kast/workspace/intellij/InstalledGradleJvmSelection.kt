package io.github.amichne.kast.workspace.intellij

import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionReport
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionFailure
import io.github.amichne.kast.distribution.contract.gradle.GradleJvmSelectionOutcome
import io.github.amichne.kast.distribution.contract.gradle.GradleDistributionEvidence
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

internal typealias InstalledGradleJvmSelectionFailure = GradleJvmSelectionFailure

internal sealed interface InstalledGradleJvmSelection {
    val report: GradleJvmSelectionReport
    data class Selected(
        val jvm: SelectedGradleJvm,
        override val report: GradleJvmSelectionReport,
    ) : InstalledGradleJvmSelection

    data class Rejected(
        val failure: InstalledGradleJvmSelectionFailure,
        override val report: GradleJvmSelectionReport = GradleJvmSelectionReport(
            GradleDistributionEvidence.Unavailable, emptyList(), emptyList(),
            GradleJvmSelectionOutcome.Rejected(failure),
        ),
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
    projectJvmAuthority: ProjectGradleJvmAuthority,
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

    fun reject(
        failure: InstalledGradleJvmSelectionFailure,
        candidates: List<GradleJvmCandidate> = emptyList(),
    ) = InstalledGradleJvmSelection.Rejected(
        failure,
        gradleJvmRejectionReport(distribution, candidates, failure),
    )

    val root = try {
        Path.of(settings.externalProjectPath).toRealPath()
    } catch (_: IOException) {
        return reject(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    } catch (_: InvalidPathException) {
        return reject(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    } catch (_: SecurityException) {
        return reject(
            InstalledGradleJvmSelectionFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
        )
    }

    val repositoryHome = when (val configured = projectJvmAuthority) {
        ProjectGradleJvmAuthority.Absent -> null
        is ProjectGradleJvmAuthority.Present -> configured.home
        ProjectGradleJvmAuthority.Rejected -> return reject(
            InstalledGradleJvmSelectionFailure.REPOSITORY_JAVA_HOME_INVALID,
        )
    }
    val repositoryCandidate = repositoryHome?.let { home ->
        observeGradleJvmCandidate(home, GradleJvmSelectionSource.REPOSITORY_GRADLE_PROPERTY)
            ?: return reject(
                InstalledGradleJvmSelectionFailure.REPOSITORY_JAVA_HOME_INVALID,
            )
    }
    val sidecarCandidate = observeGradleJvmCandidate(
        sidecar.home,
        GradleJvmSelectionSource.SIDECAR_COMPATIBLE,
    ) ?: return reject(
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
        return reject(
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
        RepositoryDaemonJvmCriteria.Rejected -> return reject(
            InstalledGradleJvmSelectionFailure.DAEMON_JVM_CRITERIA_UNSUPPORTED,
        )
    }
    val proof = when (
        val selection = GradleJvmCandidateSelector.select(distribution, selectionCandidates)
    ) {
        is GradleJvmCandidateSelection.Selected -> selection
        is GradleJvmCandidateSelection.Rejected -> return reject(
            InstalledGradleJvmSelectionFailure.NO_COMPATIBLE_RUNTIME,
            candidates,
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
            return reject(
                InstalledGradleJvmSelectionFailure.SDK_REGISTRATION_FAILED,
                candidates,
            )
        }
    }
    return InstalledGradleJvmSelection.Selected(
        SelectedGradleJvm.establish(proof, selector),
        gradleJvmSelectedReport(proof, candidates),
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

private val DAEMON_JVM_CRITERIA_MINIMUM_GRADLE =
    org.gradle.util.GradleVersion.version("8.8")
private const val MAX_DAEMON_JVM_CRITERIA_BYTES = 1_048_576L
