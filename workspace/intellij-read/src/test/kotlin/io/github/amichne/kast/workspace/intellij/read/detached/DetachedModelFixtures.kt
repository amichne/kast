package io.github.amichne.kast.workspace.intellij.read

import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission

internal val DETACHED_FIXTURE_COMPATIBILITY: AdmittedIdeHostCompatibility = when (
    val admitted = FIXTURE_COMPATIBILITY_POLICY.admit(FIXTURE_COMPATIBILITY)
) {
    is IdeHostCompatibilityAdmission.Admitted -> admitted.compatibility
    is IdeHostCompatibilityAdmission.Rejected -> error(
        "invalid detached-model compatibility fixture: ${admitted.failure}",
    )
}

internal fun detachedModelBoundary(
    disposed: Boolean = false,
    smart: Boolean = true,
    projectRoot: String? = FIXTURE_ROOT.value,
    gradleModelComplete: Boolean = true,
    modules: List<DetachedModuleBoundary> = listOf(detachedModuleBoundary()),
): DetachedModelBoundary = DetachedModelBoundary(
    disposed = disposed,
    smart = smart,
    projectRoot = projectRoot,
    gradleModelComplete = gradleModelComplete,
    modules = modules,
)

internal fun detachedModuleBoundary(
    index: Int = 0,
    disposed: Boolean = false,
    name: String = "kast.module$index",
    gradleOwned: Boolean = true,
    gradleBuildRoot: String? = FIXTURE_ROOT.value,
    gradleProjectRoot: String? = "${FIXTURE_ROOT.value}/module$index",
    gradleProjectIdentity: String? = ":module$index",
    sourceRoots: List<DetachedSourceRootBoundary> = listOf(
        detachedSourceRootBoundary("module$index/src/main/kotlin"),
    ),
    sdk: DetachedSdkBoundary? = detachedSdkBoundary(),
    classpath: List<DetachedClasspathBoundary> = listOf(detachedClasspathBoundary(index)),
): DetachedModuleBoundary = DetachedModuleBoundary(
    disposed = disposed,
    name = name,
    gradleOwned = gradleOwned,
    gradleBuildRoot = gradleBuildRoot,
    gradleProjectRoot = gradleProjectRoot,
    gradleProjectIdentity = gradleProjectIdentity,
    sourceRoots = sourceRoots,
    sdk = sdk,
    classpath = classpath,
)

internal fun detachedSourceRootBoundary(
    relativePath: String,
    kind: DetachedSourceRootKind = DetachedSourceRootKind.PRODUCTION,
): DetachedSourceRootBoundary = DetachedSourceRootBoundary(
    path = "${FIXTURE_ROOT.value}/$relativePath",
    kind = kind,
)

internal fun detachedSdkBoundary(
    name: String = "Fixture JDK 21",
    type: String = "JavaSDK",
    version: String? = "21.0.7",
): DetachedSdkBoundary = DetachedSdkBoundary(name, type, version)

internal fun detachedClasspathBoundary(
    index: Int,
    url: String = "file:///workspace/kast/.fixture/classpath-$index.jar",
): DetachedClasspathBoundary = DetachedClasspathBoundary(url)

internal fun captureDetachedFixture(
    observation: DetachedModelObservation,
): DetachedModelCapture = DetachedIdeWorkspaceModel.admit(
    FIXTURE_ROOT,
    DETACHED_FIXTURE_COMPATIBILITY,
    observation,
)

internal val EXPECTED_DETACHED_MODEL_REPORT = """
    {
        "schemaVersion": 1,
        "authority": "OPEN_PROJECT",
        "canonicalRoot": "/workspace/kast",
        "captureMode": "CANCELLABLE_WRITE_PRIORITY_READ",
        "modelState": "COMPLETE_DETACHED",
        "retainedFacets": [
            "ROOT",
            "MODULES",
            "SOURCE_ROOTS",
            "GRADLE_OWNERSHIP",
            "SDK",
            "CLASSPATH_IDENTITY",
            "HOST_COMPATIBILITY"
        ],
        "rejectedLiveCapabilities": [
            "PROJECT",
            "VIRTUAL_FILE",
            "MODULE",
            "SEARCH_SCOPE",
            "PSI",
            "GRADLE_DATA_NODE",
            "CALLBACK",
            "MUTABLE_COLLECTION"
        ],
        "maxCachedGradleModelCount": 8,
        "maxModuleCount": 128,
        "maxSourceRootCountPerModule": 256,
        "maxClasspathEntryCountPerModule": 512,
        "maxIdentityUtf8Bytes": 512,
        "maxPathUtf8Bytes": 4096,
        "maxClasspathUrlUtf8Bytes": 8192,
        "gradleImportCount": 0,
        "gradleLinkCount": 0,
        "gradlePrepareCount": 0,
        "gradleRepairCount": 0,
        "vfsRefreshCount": 0,
        "repositoryWalkCount": 0,
        "sourceHashCount": 0,
        "blockingWaitCount": 0,
        "liveObjectEscapeCount": 0,
        "edtSemanticWorkCount": 0,
        "productionEpochFieldCount": 0
    }
""".trimIndent() + "\n"
