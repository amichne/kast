package io.github.amichne.kast.workspace.intellij.read

import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.psi.KtElement

/** The native analysis session and its callback share one synchronous accounted effect. */
inline fun <Value> IntellijReadObservation.observedAnalyze(
    element: KtElement,
    crossinline action: KaSession.() -> Value,
): Value = call(IntellijReadCall.K2_ANALYSIS) { analyze(element) { action() } }

inline fun <Value> IntellijReadObservation.observedAnalyze(
    module: KaModule,
    crossinline action: KaSession.() -> Value,
): Value = call(IntellijReadCall.K2_ANALYSIS) { analyze(module) { action() } }
