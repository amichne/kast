package io.github.amichne.kast.idea

import com.intellij.openapi.project.Project
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class IdeaIndexSemanticAdmissionTest {
    @Test
    fun `index admission waits until a Kotlin compiler model is semantically usable`() {
        var nowNanos = 0L
        var attempts = 0
        val admission = IdeaIndexSemanticAdmission(
            project = projectStub(),
            inspectProject = {
                attempts += 1
                if (attempts == 1) {
                    IdeaIndexSemanticAdmission.Inspection.Pending("kotlin runtime unresolved")
                } else {
                    IdeaIndexSemanticAdmission.Inspection.Ready
                }
            },
            nanoTime = { nowNanos },
            pause = { millis -> nowNanos += millis * 1_000_000L },
            maxWaitMillis = 1_000,
            pollIntervalMillis = 25,
        )

        assertTrue(admission.status() is IdeaIndexSemanticAdmission.Status.Pending)

        admission.await { false }

        assertEquals(2, attempts)
        assertEquals(25_000_000L, nowNanos)
        assertEquals(IdeaIndexSemanticAdmission.Status.Ready, admission.status())
    }

    @Test
    fun `index admission fails typed instead of publishing ready after timeout`() {
        var nowNanos = 0L
        val admission = IdeaIndexSemanticAdmission(
            project = projectStub(),
            inspectProject = {
                IdeaIndexSemanticAdmission.Inspection.Pending("JDK symbol java.nio.file.Path unresolved in :app")
            },
            nanoTime = { nowNanos },
            pause = { millis -> nowNanos += millis * 1_000_000L },
            maxWaitMillis = 50,
            pollIntervalMillis = 25,
        )

        val failure = assertThrows(IllegalStateException::class.java) {
            admission.await { false }
        }

        assertTrue(failure.message.orEmpty().contains("java.nio.file.Path"))
        assertTrue(
            (admission.status() as IdeaIndexSemanticAdmission.Status.Failed)
                .detail
                .contains("java.nio.file.Path"),
        )
    }

    private fun projectStub(): Project =
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
}
