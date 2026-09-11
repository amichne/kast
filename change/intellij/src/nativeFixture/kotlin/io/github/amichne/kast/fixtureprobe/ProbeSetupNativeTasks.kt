package io.github.amichne.kast.fixtureprobe

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.DumbServiceImpl
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdater
import com.intellij.openapi.roots.impl.PushedFilePropertiesUpdaterImpl
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Pinned-platform preparation, invoked on the fixture worker without a read or write action. */
internal object ProbeSetupNativeTasks {
    fun drain(project: Project, deadline: Long): ProbeResult<Unit> {
        val updater =
            PushedFilePropertiesUpdater.getInstance(project) as? PushedFilePropertiesUpdaterImpl
                ?: return ProbeResult.Rejected(ProbeFailure.SETUP_NATIVE_TASKS_UNAVAILABLE)
        if (DumbService.getInstance(project) !is DumbServiceImpl)
            return ProbeResult.Rejected(ProbeFailure.SETUP_NATIVE_TASKS_UNAVAILABLE)
        val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (remainingMillis <= 0) return ProbeResult.Rejected(ProbeFailure.SETUP_TIMEOUT)
        return try {
            runBlocking { withTimeout(remainingMillis) { updater.performDelayedPushTasks() } }
            ProbeResult.Accepted(Unit)
        } catch (_: TimeoutCancellationException) {
            ProbeResult.Rejected(ProbeFailure.SETUP_TIMEOUT)
        }
    }

    fun indexingState(project: Project): ProbeSetupIndexingState {
        val service = DumbService.getInstance(project) as? DumbServiceImpl ?: return ProbeSetupIndexingState.UNAVAILABLE
        return ProbeSetupIndexingState.observe(isDumb = service.isDumb, hasScheduledTasks = service.hasScheduledTasks())
    }
}
