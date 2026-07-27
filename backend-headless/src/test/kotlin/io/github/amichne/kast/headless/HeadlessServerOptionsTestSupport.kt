package io.github.amichne.kast.headless

import com.intellij.openapi.project.Project
import java.lang.reflect.Proxy
import java.time.Duration

internal fun modelReadiness(
    moduleNames: List<String> = emptyList(),
    kotlinSourceModuleNames: List<String> = moduleNames,
    compilerReadyKotlinModuleNames: List<String> = kotlinSourceModuleNames,
): HeadlessGradleModelReadiness = HeadlessGradleModelReadiness(
    moduleNames = moduleNames.sorted(),
    kotlinSourceModuleNames = kotlinSourceModuleNames.sorted(),
    compilerReadyKotlinModuleNames = compilerReadyKotlinModuleNames.sorted(),
)

internal fun settlementEvidence(): HeadlessGradleModelSettlementEvidence {
    val observation = HeadlessGradleImportObservation(
        reload = HeadlessGradleReloadState.COMPLETED,
        resolve = HeadlessGradleResolveState.IDLE,
        index = HeadlessIdeaIndexState.SMART,
        lifecycle = HeadlessProjectLifecycleState.ACTIVE,
    )
    return HeadlessGradleModelSettlementEvidence(
        lastObservation = observation,
        recentTransitions = listOf(
            HeadlessGradleImportTransition(
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
