package io.github.amichne.kast.relation.intellij

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.CallbackParameterIdentity
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationOccurrence
import io.github.amichne.kast.relation.contract.ValueArgumentPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CallbackFormalWorklistTest {
    @Test
    fun `self cycle mutual cycle and diamond drain each exact formal once in discovery order`() {
        val fixture = RelationReadTest()
        val request = fixture.request(RelationMeaning.Callees)
        val formals =
            listOf("root", "left", "right", "terminal").map { name ->
                val endpoint = fixture.fact(request, "sample.Related.$name()").target as RelationEndpoint.Resolved
                CallbackParameterIdentity.fromCompiler(
                        endpoint,
                        ValueArgumentPosition.parse(0).refined(),
                        RelationOccurrence.fromBoundary(endpoint.file, 72, 73).refined(),
                    )
                    .refined()
            }
        val (root, left, right) = formals
        val terminal = formals[3]
        val outgoing =
            mapOf(
                root to listOf(root, left, right),
                left to listOf(root, terminal),
                right to listOf(terminal),
                terminal to emptyList(),
            )
        val worklist = CallbackFormalWorklist(root, root)
        val visited = mutableListOf<CallbackParameterIdentity>()
        val schedules = mutableListOf<CallbackFormalSchedule>()
        while (true) {
            when (val work = worklist.next()) {
                CallbackFormalWork.Exhausted -> break
                is CallbackFormalWork.Pending -> {
                    visited += work.value
                    for (next in outgoing.getValue(work.value)) schedules += worklist.schedule(next, next)
                }
            }
        }
        assertEquals(formals, visited)
        assertEquals(formals, worklist.formals)
        assertEquals(
            listOf(
                CallbackFormalSchedule.ALREADY_DISCOVERED,
                CallbackFormalSchedule.SCHEDULED,
                CallbackFormalSchedule.SCHEDULED,
                CallbackFormalSchedule.ALREADY_DISCOVERED,
                CallbackFormalSchedule.SCHEDULED,
                CallbackFormalSchedule.ALREADY_DISCOVERED,
            ),
            schedules,
        )
        assertEquals(CallbackFormalWork.Exhausted, worklist.next())
    }
}

private fun <V, F> Refinement<V, F>.refined(): V =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Fixture rejection: $failure")
    }
