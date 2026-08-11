package io.github.amichne.kast.indexer.gradle.bootstrap

import com.intellij.openapi.project.Project
import io.github.amichne.kast.indexer.gradle.settlement.GradleImportObservation
import io.github.amichne.kast.indexer.gradle.settlement.GradleImportTransition
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelReadiness
import io.github.amichne.kast.indexer.gradle.settlement.GradleModelSettlementEvidence
import io.github.amichne.kast.indexer.project.IdeaIndexState
import io.github.amichne.kast.indexer.project.ProjectLifecycleState
import io.github.amichne.kast.idea.transition.BuildSemanticInputIdentity
import io.github.amichne.kast.indexer.project.WorkspaceKind
import java.nio.file.Path
import java.lang.reflect.Proxy
import java.time.Duration

internal fun modelReadiness(
    moduleNames: List<String> = emptyList(),
    kotlinSourceModuleNames: List<String> = moduleNames,
    compilerReadyKotlinModuleNames: List<String> = kotlinSourceModuleNames,
): GradleModelReadiness = GradleModelReadiness(
    moduleNames = moduleNames.sorted(),
    kotlinSourceModuleNames = kotlinSourceModuleNames.sorted(),
    compilerReadyKotlinModuleNames = compilerReadyKotlinModuleNames.sorted(),
)

internal fun settlementEvidence(): GradleModelSettlementEvidence {
    val observation = GradleImportObservation(
        reload = GradleReloadState.COMPLETED,
        resolve = GradleResolveState.IDLE,
        index = IdeaIndexState.SMART,
        lifecycle = ProjectLifecycleState.ACTIVE,
    )
    return GradleModelSettlementEvidence(
        lastObservation = observation,
        recentTransitions = listOf(
            GradleImportTransition(
                observation = observation,
                firstObservedAt = Duration.ZERO,
                lastObservedAt = Duration.ZERO,
                occurrenceCount = 10,
            ),
        ),
        elapsed = Duration.ofMillis(900),
        totalObservations = 10,
        totalTransitions = 0,
        stableObservations = 10,
    )
}

internal fun projectStub(): Project =
    Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getName" -> "stub"
            "isDisposed" -> false
            "hashCode" -> 0
            "equals" -> false
            "toString" -> "ProjectStub"
            else -> null
        }
    } as Project

internal fun readyInitialProjectModel(identity: BuildSemanticInputIdentity): InitialProjectModelAuthority {
    val bootstrap = GradleProjectBootstrap(
        configureGradleImport = {},
        waitForProjectModel = { settlementEvidence() },
        inspectProjectModel = { modelReadiness(moduleNames = listOf(":app")) },
        canLinkGradleProject = { _, _ -> true },
        hasLinkedGradleProject = { _, _ -> true },
        captureBuildSemanticInputIdentity = { _, _ -> identity },
    )
    return bootstrap.bootstrapProject(
        project = projectStub(),
        workspaceRoot = Path.of("/test-workspace"),
        workspaceKind = WorkspaceKind.GRADLE,
    ).initialProjectModelAuthority
}
