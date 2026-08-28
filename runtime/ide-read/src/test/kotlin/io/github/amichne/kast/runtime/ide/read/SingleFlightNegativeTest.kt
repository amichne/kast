package io.github.amichne.kast.runtime.ide.read

import java.lang.reflect.Modifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SingleFlightNegativeTest {
    @Test
    fun `simultaneous demand cannot exceed one active and one queued`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        val results = try {
            val futures = List(8) {
                val freshness = fixture.capability()
                executor.submit<ProjectReadAdmission> {
                    start.await()
                    controller.admit(freshness)
                }
            }
            start.countDown()
            futures.map { future -> future.get() }
        } finally {
            executor.shutdownNow()
        }

        assertEquals(1, results.count { it is ProjectReadAdmission.Active })
        assertEquals(1, results.count { it is ProjectReadAdmission.Queued })
        assertEquals(
            6,
            results.count {
                it == ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.Busy)
            },
        )
    }

    @Test
    fun `busy and foreign demand cannot mutate owned authority`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val active = active(controller.admit(fixture.capability()))
        val queued = queued(controller.admit(fixture.capability()))

        assertEquals(
            ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.Busy),
            controller.admit(fixture.capability()),
        )
        assertEquals(
            QueuedProjectReadCancellation.Cancelled(
                ProjectReadCancellationCause.REQUEST_CANCELLED,
            ),
            controller.cancelQueued(queued, ProjectReadCancellationCause.REQUEST_CANCELLED),
        )
        assertTrue(controller.admit(fixture.capability()) is ProjectReadAdmission.Queued)

        val otherFixture = FreshnessFixture("/tmp/kast-single-flight-foreign")
        val other = controller(otherFixture.capability())
        val foreignPermit = active(other.admit(otherFixture.capability()))
        val foreignQueue = queued(other.admit(otherFixture.capability()))
        assertEquals(ProjectReadPermitEnd.NotOwned, controller.release(foreignPermit))
        assertEquals(
            QueuedProjectReadCancellation.NotOwned,
            controller.cancelQueued(
                foreignQueue,
                ProjectReadCancellationCause.CLIENT_DISCONNECTED,
            ),
        )
        assertSame(active, (controller.retire(ProjectReadRetirementCause.PLUGIN_UNLOADED)
            as ProjectReadRetirement.Retired).let { retired ->
                (retired.authority as RetiredProjectReadAuthority.ActiveAndQueued).permit
            })
    }

    @Test
    fun `stale handles cannot terminalize or promote twice`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val first = active(controller.admit(fixture.capability()))
        val queued = queued(controller.admit(fixture.capability()))
        val firstEnd = controller.release(first) as ProjectReadPermitEnd.Ended
        val promotion = firstEnd.continuation as ProjectReadContinuation.Promoted

        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Released),
            controller.cancel(first, ProjectReadCancellationCause.REQUEST_CANCELLED),
        )
        assertEquals(
            QueuedProjectReadCancellation.AlreadyTerminal(
                QueuedProjectReadTerminal.Promoted(promotion.permit),
            ),
            controller.cancelQueued(queued, ProjectReadCancellationCause.CLIENT_DISCONNECTED),
        )
        assertTrue(controller.release(promotion.permit) is ProjectReadPermitEnd.Ended)
        assertEquals(
            ProjectReadPermitEnd.AlreadyEnded(ProjectReadPermitTerminal.Released),
            controller.release(promotion.permit),
        )
    }

    @Test
    fun `wrong root and incomparable source fail without consuming capacity`() {
        val fixture = FreshnessFixture()
        val controller = controller(fixture.capability())
        val wrongRoot = FreshnessFixture("/tmp/kast-single-flight-other")
        val otherSource = FreshnessFixture()

        assertEquals(
            ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.WrongProject),
            controller.admit(wrongRoot.capability()),
        )
        assertEquals(
            ProjectReadAdmission.Rejected(ProjectReadAdmissionFailure.IncomparableProjectSource),
            controller.admit(otherSource.capability()),
        )
        assertTrue(controller.admit(fixture.capability()) is ProjectReadAdmission.Active)
    }

    @Test
    fun `permit surface is non forgeable and retains no forbidden authority`() {
        assertTrue(Modifier.isFinal(ProjectReadPermit::class.java.modifiers))
        assertTrue(ProjectReadPermit::class.java.declaredConstructors.isNotEmpty())
        assertTrue(ProjectReadPermit::class.java.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic
        })
        assertTrue(Modifier.isFinal(QueuedProjectReadRequest::class.java.modifiers))
        assertTrue(QueuedProjectReadRequest::class.java.declaredConstructors.isNotEmpty())
        assertTrue(QueuedProjectReadRequest::class.java.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic
        })
        assertTrue(ProjectReadSingleFlight::class.java.declaredConstructors.all { constructor ->
            Modifier.isPrivate(constructor.modifiers) || constructor.isSynthetic
        })
        assertFalse(ProjectReadPermit::class.java.declaredMethods.any { method ->
            !method.isSynthetic &&
                (method.name == "copy" || method.name.startsWith("component"))
        })
        assertFalse(ProjectReadPermit::class.java.declaredMethods.any { method ->
            !Modifier.isPrivate(method.modifiers) && (
                method.name.startsWith("issue") ||
                    method.name.startsWith("end") ||
                    method.name.startsWith("getFreshness")
                )
        })
        assertFalse(QueuedProjectReadRequest::class.java.declaredMethods.any { method ->
            method.name.startsWith("issue") ||
                method.name.startsWith("terminalize") ||
                method.name.startsWith("getFreshness")
        })
        assertFalse(ProjectReadSingleFlight::class.java.declaredMethods.any { method ->
            method.name.startsWith("install")
        })

        val ownedTypes = sequenceOf(
            ProjectReadPermit::class.java,
            QueuedProjectReadRequest::class.java,
            ProjectReadSingleFlight::class.java,
        ).flatMap { type ->
            type.declaredFields.asSequence()
                .filterNot { field -> Modifier.isStatic(field.modifiers) }
                .map { field -> field.type.name }
        }.toSet()
        val forbidden = listOf(
            "com.intellij.openapi.project.Project",
            "kotlin.jvm.functions.Function",
            "kotlinx.coroutines",
            "java.util.concurrent.Executor",
            "java.util.Collection",
            "java.util.Map",
        )
        assertTrue(forbidden.none { prefix -> ownedTypes.any { it.startsWith(prefix) } })
        assertTrue(ProjectReadSingleFlight::class.java.declaredFields
            .filter { field -> Modifier.isStatic(field.modifiers) }
            .none { field ->
                field.type.name.contains("Map") || field.type.name.contains("Lock")
            })
    }
}
