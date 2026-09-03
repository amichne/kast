package io.github.amichne.kast.workspace.intellij.read

import com.intellij.openapi.project.Project
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.IdeBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityCandidate
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.KotlinPluginBuildIdentity
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import java.lang.reflect.Proxy
import java.nio.file.Path

internal val FIXTURE_ROOT = fixtureRoot("/workspace/kast")
internal val OTHER_FIXTURE_ROOT = fixtureRoot("/workspace/other")
internal val FIXTURE_COMPATIBILITY = IdeHostCompatibilityCandidate(
    ideBuild = "262.9437.185",
    kotlinPluginBuild = "262.9437.185-IJ",
    kastPluginVersion = "1.2.3",
    runtimeProtocolIdentity = "kast.ide-hosted.runtime.v1",
    operationRegistryDigest = "sha256:" + "1".repeat(64),
    wireSchemaDigest = "sha256:" + "2".repeat(64),
    capabilities = listOf(
        "index.sync",
        "topology.build",
        "symbol.discover",
        "symbol.inspect",
        "source.read",
        "relation.read",
        "traversal.run",
        "diagnostic.check",
        "change.plan",
        "change.apply",
        "change.recover",
    ),
)

internal val FIXTURE_COMPATIBILITY_POLICY = when (
    val result = IdeHostCompatibilityPolicy.define(FIXTURE_COMPATIBILITY)
) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> error("invalid compatibility fixture: ${result.failure}")
}

internal val FIXTURE_EPOCH_SOURCE_FACTORY = ExistingProjectReadEpochSourceFactory { _, _ ->
    Refinement.Refined(
        ProjectReadEpoch.Source.create { ProjectReadEpochState.admit(stableFixtureEpochBoundary()) },
    )
}

internal val EXPECTED_PROJECT_ADMISSION_REPORT = """
    {
        "schemaVersion": 1,
        "authority": "EXISTING_IDE_PROJECT",
        "canonicalRoot": "/workspace/kast",
        "projectLifecycle": "OPEN_INITIALIZED",
        "gradleModel": "COMPLETE",
        "indexingState": "SMART",
        "kotlinMode": "K2",
        "hostCompatibility": "EXACT",
        "ideBuild": "262.9437.185",
        "kotlinPluginBuild": "262.9437.185-IJ",
        "projectOpenCount": 0,
        "gradleLinkCount": 0,
        "gradleImportCount": 0,
        "vfsRefreshCount": 0,
        "indexingWaitCount": 0,
        "repositoryWalkCount": 0,
        "sourceHashCount": 0
    }
""".trimIndent() + "\n"

internal class RecordingProjectObservation(
    var disposed: Boolean = false,
    var open: Boolean = true,
    var initialized: Boolean = true,
    var projectRoot: ExistingProjectRootObservation =
        ExistingProjectRootObservation.Available(FIXTURE_ROOT),
    var gradleModelState: ExistingProjectGradleModelState =
        ExistingProjectGradleModelState.COMPLETE,
    var indexingState: ExistingProjectIndexingState = ExistingProjectIndexingState.SMART,
    var kotlinModeState: ExistingProjectKotlinMode = ExistingProjectKotlinMode.K2,
    var hostIdentity: ExistingProjectHostIdentityObservation = fixtureHostIdentity(),
    var throwAt: ExistingProjectObservationStage? = null,
    var thrownFailure: RuntimeException = IllegalStateException("fixture observation failure"),
) : ExistingProjectObservationPort {
    val observedStages = mutableListOf<ExistingProjectObservationStage>()

    override fun isDisposed(project: Project): Boolean = observe(
        ExistingProjectObservationStage.DISPOSAL,
        disposed,
    )

    override fun isOpen(project: Project): Boolean = observe(
        ExistingProjectObservationStage.OPEN,
        open,
    )

    override fun isInitialized(project: Project): Boolean = observe(
        ExistingProjectObservationStage.INITIALIZATION,
        initialized,
    )

    override fun root(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): ExistingProjectRootObservation = observe(
        ExistingProjectObservationStage.ROOT,
        projectRoot,
    )

    override fun gradleModel(
        project: Project,
        expectedRoot: CanonicalWorkspaceRoot,
    ): ExistingProjectGradleModelState = observe(
        ExistingProjectObservationStage.GRADLE_MODEL,
        gradleModelState,
    )

    override fun indexing(project: Project): ExistingProjectIndexingState = observe(
        ExistingProjectObservationStage.INDEXING,
        indexingState,
    )

    override fun kotlinMode(): ExistingProjectKotlinMode = observe(
        ExistingProjectObservationStage.KOTLIN_MODE,
        kotlinModeState,
    )

    override fun hostIdentity(): ExistingProjectHostIdentityObservation = observe(
        ExistingProjectObservationStage.HOST_IDENTITY,
        hostIdentity,
    )

    private fun <Value> observe(
        stage: ExistingProjectObservationStage,
        value: Value,
    ): Value {
        observedStages += stage
        if (throwAt == stage) throw thrownFailure
        return value
    }
}

internal fun opaqueProject(): Project = proxyProject { methodName ->
    error("live Project method unexpectedly invoked: $methodName")
}

internal fun projectWithBasePath(basePath: String?): Project = proxyProject { methodName ->
    if (methodName == "getBasePath") basePath else error(
        "Project method unexpectedly invoked while observing root: $methodName",
    )
}

internal fun disposedProject(): Project = proxyProject { methodName ->
    if (methodName == "isDisposed") true else error(
        "Project method unexpectedly invoked while rejecting disposed source: $methodName",
    )
}

internal fun admittedFailure(
    result: ExistingProjectAdmission,
): ExistingProjectAdmissionFailure = when (result) {
    is ExistingProjectAdmission.Admitted -> error("fixture Project unexpectedly admitted")
    is ExistingProjectAdmission.Rejected -> result.failure
}

internal fun fixtureHostIdentity(
    ideBuild: String = FIXTURE_COMPATIBILITY.ideBuild,
    kotlinBuild: String = FIXTURE_COMPATIBILITY.kotlinPluginBuild,
): ExistingProjectHostIdentityObservation.Available {
    val ide = when (val result = IdeBuildIdentity.parse(ideBuild)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("invalid IDE build fixture: ${result.failure}")
    }
    val kotlin = when (val result = KotlinPluginBuildIdentity.parse(kotlinBuild)) {
        is Refinement.Refined -> result.value
        is Refinement.Rejected -> error("invalid Kotlin build fixture: ${result.failure}")
    }
    return ExistingProjectHostIdentityObservation.Available(ide, kotlin)
}

private fun fixtureRoot(raw: String): CanonicalWorkspaceRoot = when (
    val result = CanonicalWorkspaceRoot.fromCanonicalPath(Path.of(raw))
) {
    is Refinement.Refined -> result.value
    is Refinement.Rejected -> error("invalid root fixture: ${result.failure}")
}

private fun stableFixtureEpochBoundary() = ProjectReadEpochBoundary(
    ProjectReadEpochSignalSample.Value(1),
    fixtureProjectEpochRoot(FIXTURE_ROOT.value),
    fixtureGradleEpochRoot(FIXTURE_ROOT.value),
    1,
    1,
    ProjectReadEpochSignalSample.Value(1),
    ProjectReadEpochSignalSample.Value(1),
    ProjectReadEpochSignalSample.Value(1),
    ProjectReadEpochSignalSample.Value(1),
    false,
)

private fun proxyProject(read: (String) -> Any?): Project = Proxy.newProxyInstance(
    Project::class.java.classLoader,
    arrayOf(Project::class.java),
) { proxy, method, arguments ->
    when (method.name) {
        "toString" -> "OpaqueFixtureProject"
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> arguments?.singleOrNull() === proxy
        else -> read(method.name)
    }
} as Project
