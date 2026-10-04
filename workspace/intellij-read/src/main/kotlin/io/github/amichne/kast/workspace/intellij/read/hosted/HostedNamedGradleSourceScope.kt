package io.github.amichne.kast.workspace.intellij.read.hosted

import io.github.amichne.kast.kernel.ReadLimits
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.workspace.contract.ProjectReadEpoch
import io.github.amichne.kast.workspace.contract.ProjectReadEpochRelation
import io.github.amichne.kast.workspace.intellij.read.AdmittedIdeProject
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScope
import io.github.amichne.kast.workspace.intellij.read.NamedGradleSourceScopeFailure
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One endpoint's detached imported model; live freshness remains an obligation on every read. */
internal class HostedNamedGradleSourceScope {
    private val mutex = Mutex()
    private var state: Retention = Retention.Empty

    suspend fun capture(
        admitted: AdmittedIdeProject,
        epoch: ProjectReadEpoch<*>,
        progress: HostedQueryProgress,
        freshness: HostedReadFreshness,
    ): Refinement<NamedGradleSourceScope, HostedQueryFailure> =
        capture(
            epoch,
            progress.limits,
            progress.observation,
            freshness.current,
        ) {
            admitted.captureNamedGradleSourceScope(progress.observation, progress.limits)
        }

    suspend fun capture(
        epoch: ProjectReadEpoch<*>,
        limits: ReadLimits,
        observation: IntellijReadObservation,
        validate: suspend () -> Refinement<Unit, HostedQueryFailure>,
        observe: suspend () -> Refinement<NamedGradleSourceScope, NamedGradleSourceScopeFailure>,
    ): Refinement<NamedGradleSourceScope, HostedQueryFailure> = mutex.withLock {
        when (val current = validate()) {
            is Refinement.Rejected -> {
                state = Retention.Empty
                return@withLock current
            }
            is Refinement.Refined -> Unit
        }
        when (val retained = retainedFor(epoch, limits)) {
            Retention.Empty -> captureCurrent(epoch, limits, validate, observe)
            is Retention.Captured -> {
                // No enumeration occurred on this request. Missing observations must never stand in for zero.
                for (counter in MODEL_ENUMERATION_COUNTERS) observation.count(counter, amount = 0)
                Refinement.Refined(retained.scope)
            }
        }
    }

    private fun retainedFor(epoch: ProjectReadEpoch<*>, limits: ReadLimits): Retention =
        when (val retained = state) {
            Retention.Empty -> Retention.Empty
            is Retention.Captured ->
                if (retained.limits === limits && retained.epoch.relationTo(epoch) == ProjectReadEpochRelation.SAME)
                    retained
                else Retention.Empty
        }

    private suspend fun captureCurrent(
        epoch: ProjectReadEpoch<*>,
        limits: ReadLimits,
        validate: suspend () -> Refinement<Unit, HostedQueryFailure>,
        observe: suspend () -> Refinement<NamedGradleSourceScope, NamedGradleSourceScopeFailure>,
    ): Refinement<NamedGradleSourceScope, HostedQueryFailure> {
        state = Retention.Empty
        return when (val captured = observe()) {
            is Refinement.Rejected -> Refinement.Rejected(HostedQueryFailure.NamedSourceScope(captured.failure))
            is Refinement.Refined ->
                when (val current = validate()) {
                    is Refinement.Rejected -> current
                    is Refinement.Refined -> {
                        state = Retention.Captured(epoch, limits, captured.value)
                        captured
                    }
                }
        }
    }

    private sealed interface Retention {
        data object Empty : Retention

        class Captured(val epoch: ProjectReadEpoch<*>, val limits: ReadLimits, val scope: NamedGradleSourceScope) :
            Retention
    }

    private companion object {
        val MODEL_ENUMERATION_COUNTERS =
            listOf(
                IntellijReadCounter.IMPORTED_PROJECTS,
                IntellijReadCounter.IDEA_MODULES,
                IntellijReadCounter.SELECTED_GRADLE_MODULES,
                IntellijReadCounter.FOREIGN_GRADLE_MODULES,
                IntellijReadCounter.SOURCE_ROOTS,
            )
    }
}
