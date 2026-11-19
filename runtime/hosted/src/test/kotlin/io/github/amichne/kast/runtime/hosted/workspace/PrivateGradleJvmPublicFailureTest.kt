package io.github.amichne.kast.runtime.hosted.workspace

import io.github.amichne.kast.protocol.contract.IdeLifecycleFailure
import io.github.amichne.kast.protocol.contract.WorkspaceRefreshFailure
import io.github.amichne.kast.runtime.hosted.lifecycle.lifecycleFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PrivateGradleJvmPublicFailureTest {
    private val distribution = setOf(PrivateGradleJvmFailure.GRADLE_DISTRIBUTION_UNAVAILABLE)
    private val jvm =
        setOf(
            PrivateGradleJvmFailure.DECLARED_HOME_INVALID,
            PrivateGradleJvmFailure.DECLARED_JAVA_UNSUPPORTED,
            PrivateGradleJvmFailure.NO_COMPATIBLE_LOCAL_JDK,
            PrivateGradleJvmFailure.AMBIGUOUS_SDK_REGISTRATION,
            PrivateGradleJvmFailure.SDK_NAME_CONFLICT,
            PrivateGradleJvmFailure.HOME_NOT_INSPECTED,
        )

    @Test
    fun `every private Gradle rejection has one declared corrective family`() {
        assertEquals(PrivateGradleJvmFailure.entries.toSet(), distribution + jvm)
        assertEquals(emptySet<PrivateGradleJvmFailure>(), distribution intersect jvm)
    }

    @Test
    fun `a missing Gradle distribution reaches the lifecycle as its own failure`() {
        for (failure in distribution) {
            assertEquals(WorkspaceRefreshFailure.GRADLE_DISTRIBUTION_UNAVAILABLE, failure.publicFailure())
            assertEquals(
                IdeLifecycleFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
                failure.publicFailure().lifecycleFailure(),
            )
        }
    }

    @Test
    fun `an unavailable Gradle JVM reaches the lifecycle as its own failure`() {
        for (failure in jvm) {
            assertEquals(WorkspaceRefreshFailure.GRADLE_JVM_UNAVAILABLE, failure.publicFailure())
            assertEquals(IdeLifecycleFailure.GRADLE_JVM_UNAVAILABLE, failure.publicFailure().lifecycleFailure())
        }
    }

    @Test
    fun `rejection records the finer reason and returns the public family`() {
        assertEquals(
            WorkspaceRefreshFailure.GRADLE_DISTRIBUTION_UNAVAILABLE,
            rejectPrivateGradleJvmSettings(PrivateGradleJvmFailure.GRADLE_DISTRIBUTION_UNAVAILABLE).failure,
        )
    }
}
