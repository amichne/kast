package support.architecture.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files

internal data class ProductionSource(
    val path: String,
    val content: String,
)

internal data class LegacyArchitectureObservation(
    val projectPaths: Set<String>,
    val legacyModuleRoots: Set<String>,
    val productionSources: List<ProductionSource>,
)

internal sealed interface LegacyArchitectureFinding {
    val path: String

    data class LegacyModuleRoot(
        override val path: String,
    ) : LegacyArchitectureFinding

    data class LegacyProject(
        override val path: String,
    ) : LegacyArchitectureFinding

    data class AnalysisBackendSymbol(
        override val path: String,
    ) : LegacyArchitectureFinding

    data class CompatibilityRoute(
        override val path: String,
    ) : LegacyArchitectureFinding

    data class DuplicateLegacyAuthority(
        override val path: String,
        val marker: String,
    ) : LegacyArchitectureFinding

    data class FallbackAuthority(
        override val path: String,
    ) : LegacyArchitectureFinding
}

internal object NoLegacyArchitectureInspection {
    private val legacyProjectPaths = setOf(":analysis-api", ":analysis-server", ":index-store")
    private val duplicateAuthorityMarkers = listOf(
        "KastIndexerBackend",
        "ObservedAnalysisBackend",
        "AnalysisServer",
        "SqliteSourceIndexStore",
        "io.github.amichne.kast.api.",
        "io.github.amichne.kast.server.",
        "io.github.amichne.kast.indexstore.",
    )
    private val fallbackAuthority = Regex(
        "(?:Fallback\\w*(?:Backend|Authority|Route|Store|Server|Implementation)|" +
        "(?:Backend|Authority|Route|Store|Server|Implementation)Fallback)",
    )
    private val compatibilityRoute = Regex(
        "(?:\\bCompatibilityRoute\\b|\\bLegacy\\w*(?:Route|Binding|Adapter)\\b|" +
        "io\\.github\\.amichne\\.kast\\.[A-Za-z0-9_.]*compatibility\\.)",
    )

    /**
     * Proof transition: `LegacyArchitectureObservation -> List<LegacyArchitectureFinding>`.
     *
     * Establishes a deterministic, exhaustive finite finding list for the KCS-020 legacy
     * architecture surface. An empty list is the only accepted observation. Raw project paths
     * and source text are permitted only at the Gradle verification boundary.
     */
    fun inspect(observation: LegacyArchitectureObservation): List<LegacyArchitectureFinding> =
        buildList {
            observation.legacyModuleRoots.sorted().forEach { root ->
                add(LegacyArchitectureFinding.LegacyModuleRoot(root))
            }
            observation.projectPaths.intersect(legacyProjectPaths).sorted().forEach { project ->
                add(LegacyArchitectureFinding.LegacyProject(project))
            }
            val sources = observation.productionSources.sortedBy(ProductionSource::path)
            sources.filter { source -> "AnalysisBackend" in source.content }.forEach { source ->
                add(LegacyArchitectureFinding.AnalysisBackendSymbol(source.path))
            }
            sources.filter { source ->
                "/compatibility/" in source.path || compatibilityRoute.containsMatchIn(source.content)
            }.forEach { source ->
                add(LegacyArchitectureFinding.CompatibilityRoute(source.path))
            }
            duplicateAuthorityMarkers.forEach { marker ->
                sources.firstOrNull { source -> marker in source.content }?.let { source ->
                    add(LegacyArchitectureFinding.DuplicateLegacyAuthority(source.path, marker))
                }
            }
            sources.filter { source -> fallbackAuthority.containsMatchIn(source.content) }
                .forEach { source ->
                    add(LegacyArchitectureFinding.FallbackAuthority(source.path))
                }
        }
}

@CacheableTask
abstract class VerifyNoLegacyArchitectureTask : DefaultTask() {
    init {
        observedProjectPaths.convention(emptyList())
        observedLegacyModuleRoots.convention(emptyList())
    }

    @get:Input
    abstract val observedProjectPaths: ListProperty<String>

    @get:Input
    abstract val observedLegacyModuleRoots: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionSourceFiles: ConfigurableFileCollection

    @get:Internal
    abstract val rootDirectory: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    /**
     * Proof transition: Gradle project/source observations -> accepted clean-slate architecture.
     *
     * Establishes absence of every KCS-020 legacy marker. [LegacyArchitectureFinding] is the
     * closed expected failure set. Raw filesystem reads occur only inside this verification task.
     */
    @TaskAction
    fun verify() {
        val root = rootDirectory.get().asFile.toPath()
        val sources = productionSourceFiles.files
            .asSequence()
            .map { file -> file.toPath() }
            .filter(Files::isRegularFile)
            .map { file ->
                ProductionSource(
                    path = root.relativize(file).joinToString("/"),
                    content = Files.readString(file),
                )
            }
            .toList()
        val findings = NoLegacyArchitectureInspection.inspect(
            LegacyArchitectureObservation(
                projectPaths = observedProjectPaths.get().toSet(),
                legacyModuleRoots = observedLegacyModuleRoots.get().toSet(),
                productionSources = sources,
            ),
        )
        writeReport(findings)
        if (findings.isNotEmpty()) {
            throw GradleException(
                buildString {
                    append("Legacy architecture verification REJECTED:")
                    findings.forEach { finding ->
                        append("\n - ").append(finding.code()).append(' ').append(finding.detail())
                    }
                },
            )
        }
    }

    private fun writeReport(findings: List<LegacyArchitectureFinding>) {
        val report = reportFile.get().asFile.toPath()
        Files.createDirectories(report.parent)
        Files.writeString(
            report,
            buildString {
                append("status=").append(if (findings.isEmpty()) "ACCEPTED" else "REJECTED")
                findings.forEach { finding ->
                    append("\nfinding=").append(finding.code()).append('|').append(finding.detail())
                }
                append('\n')
            },
        )
    }
}

private fun LegacyArchitectureFinding.code(): String = when (this) {
    is LegacyArchitectureFinding.LegacyModuleRoot -> "LEGACY_MODULE_ROOT"
    is LegacyArchitectureFinding.LegacyProject -> "LEGACY_PROJECT"
    is LegacyArchitectureFinding.AnalysisBackendSymbol -> "ANALYSIS_BACKEND_SYMBOL"
    is LegacyArchitectureFinding.CompatibilityRoute -> "COMPATIBILITY_ROUTE"
    is LegacyArchitectureFinding.DuplicateLegacyAuthority -> "DUPLICATE_LEGACY_AUTHORITY"
    is LegacyArchitectureFinding.FallbackAuthority -> "FALLBACK_AUTHORITY"
}

private fun LegacyArchitectureFinding.detail(): String = when (this) {
    is LegacyArchitectureFinding.DuplicateLegacyAuthority -> "$path:$marker"
    else -> path
}
