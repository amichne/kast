package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.relation.contract.ValueCallStack
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue

/** Fixed source steps keep the original execute seed and its actual final anonymous return. */
internal class NativePacketSeedCallbackReturnRoute(
    project: Project,
    base: VirtualFile,
    fixture: NativePacketValueFixture,
) {
    private val prefix = NativePacketCallerNonNullRoute(project, base, fixture)
    private val fields = NativePacketCompositeSuccessSource(project, base, fixture)

    fun returned(): ValueSite {
        val prior = prefix.returned()
        var source = next(prior.returned, ValueTransferKind.SOURCE_CALL_RETURN)
        for (kind in FINAL_ROUTE) source = next(source, kind)
        assertEquals(exact(RESULT_START, RESULT_END), source.range)
        assertEquals(ValueRole.Return, source.role)
        assertEquals(ValueCallStack.Empty, source.callStack)
        assertTrue(source.executionOwner.body is ValueExecutionBody.Anonymous)
        assertEquals(exact(BODY_START, BODY_END), source.executionOwner.range)
        assertEquals(
            listOf(ValuePathSegment.Field(fields.field(FEATURE, "FeatureResponse", "link"))),
            source.valuePath.segments,
        )
        assertTrue(source.predicates.entries.containsAll(prior.returned.predicates.entries))
        println(
            "SEED_CALLBACK_RETURN_PREFIX seed=ORIGINAL_S2 normal_return=1029:1057 body=677:1063 " +
                "sourceStack=EMPTY path=FeatureResponse.link sourcePredicates=${source.predicates.entries.size} " +
                "seed_callback_return=NOT_RUN sequential_return=NOT_RUN"
        )
        return source
    }

    fun read(source: ValueSite): ValueFlowStep = prefix.read(source)

    private fun next(source: ValueSite, kind: ValueTransferKind): ValueSite {
        val step = read(source)
        val selected = step.transfers.filter { it.kind == kind }
        assertEquals(
            "The original S2 final prefix needs $kind at ${source.range}. Causes=${step.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val RESULT_START = 1029
        const val RESULT_END = 1057
        const val BODY_START = 677
        const val BODY_END = 1063
        val FINAL_ROUTE =
            listOf(
                ValueTransferKind.RECEIVER,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.RECEIVER,
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.BRANCH_ALTERNATIVE,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
                ValueTransferKind.LOCAL_BINDING,
                ValueTransferKind.LOCAL_READ,
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.CONSTRUCTOR_FIELD,
                ValueTransferKind.RETURN,
            )
    }
}
