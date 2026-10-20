package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValuePathSegment
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertTrue
import kotlinx.coroutines.runBlocking

/** Fixed source hops keep the original S2 producer through the actual matched requireNotNull return. */
internal class NativePacketCallerNonNullRoute(
    private val project: Project,
    base: VirtualFile,
    private val fixture: NativePacketValueFixture,
) {
    private val fields = NativePacketCompositeSuccessSource(project, base, fixture)
    private val prefix = NativePacketCompositeSuccessRoute(project, fixture, fields)

    fun returned(): CallerNonNullCase {
        val callback = prefix.enteredCallback(seed())
        val client = prefix.clientResponse(callback)
        val invoked = prefix.callbackReturn(callback, client)
        val mapped = closeBranch(factory(next(invoked, ValueTransferKind.ARGUMENT)))
        val errorReceiver = next(mapped, ValueTransferKind.RECEIVER)
        val errorFormal = next(errorReceiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val payload = next(errorFormal, ValueTransferKind.FIELD_READ)
        val errorResult = closeBranch(factory(next(payload, ValueTransferKind.ARGUMENT)))
        val clientReturn = next(errorResult, ValueTransferKind.RETURN)
        val clientCall = next(clientReturn, ValueTransferKind.SOURCE_CALL_RETURN, exact(INITIALIZER_START, CLIENT_END))
        val featureReceiver = next(clientCall, ValueTransferKind.RECEIVER)
        val featureFormal = next(featureReceiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val featurePayload = next(featureFormal, ValueTransferKind.FIELD_READ)
        var current = closeBranch(factory(next(featurePayload, ValueTransferKind.ARGUMENT)))
        assertEquals(exact(INITIALIZER_START, INITIALIZER_END), current.range)
        for (kind in COMPONENT_ROUTE) current = next(current, kind)
        val selected = application(current, exact(FIRST_START, FIRST_END))
        val receiver = next(selected, ValueTransferKind.RECEIVER)
        assertEquals(exact(FIRST_START, FIRST_END), receiver.range)
        val formal = next(receiver, ValueTransferKind.SOURCE_CALL_ENTRY)
        val checked = next(formal, ValueTransferKind.LOCAL_READ)
        assertEquals(exact(THIS_START, THIS_END), checked.range)
        val letReceiver = next(checked, ValueTransferKind.RECEIVER)
        val body = application(letReceiver)
        val it = next(body, ValueTransferKind.LOCAL_READ)
        val normal = next(factory(next(it, ValueTransferKind.ARGUMENT)), ValueTransferKind.RETURN)
        val letResult = application(normal, exact(THIS_START, LET_END))
        val branch = next(letResult, ValueTransferKind.BRANCH_ALTERNATIVE)
        val returned = next(branch, ValueTransferKind.RETURN)
        assertEquals(exact(THIS_START, ELVIS_END), returned.range)
        assertEquals(ValueRole.Return, returned.role)
        assertEquals(
            listOf(
                ValuePathSegment.Field(fields.field(NativePacketCompositeSuccessSource.OUTCOME, "Success", "value"))
            ),
            returned.valuePath.segments,
        )
        assertTrue(returned.predicates.entries.containsAll(letResult.predicates.entries))
        println("CALLER_NONNULL_PREFIX original_seed=ACTUAL component_and_models=ACTUAL matched_return=NOT_RUN")
        return CallerNonNullCase(returned = returned, receiver = receiver, checked = checked, letResult = letResult)
    }

    fun read(site: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(site, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue(
            "The original fixed source prefix must reach an actual native step",
            result is ValueFlowRead.Observed,
        )
        return (result as ValueFlowRead.Observed).step
    }

    private fun factory(argument: ValueSite): ValueSite {
        var current = argument
        for (kind in FACTORY_ROUTE) current = next(current, kind)
        return current
    }

    private fun closeBranch(expression: ValueSite): ValueSite {
        val branch = next(expression, ValueTransferKind.BRANCH_ALTERNATIVE)
        val returned = next(branch, ValueTransferKind.RETURN)
        return next(returned, ValueTransferKind.SOURCE_CALL_RETURN)
    }

    private fun application(site: ValueSite, expected: ExactDeclarationTextRange? = null): ValueSite {
        val result = read(site)
        val selected = result.applications.filter { expected == null || it.target.range == expected }
        assertEquals(
            "The actual original model prefix is missing. Causes=${result.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        assertEquals(site, selected.single().source)
        return selected.single().target
    }

    private fun next(
        site: ValueSite,
        kind: ValueTransferKind,
        expected: ExactDeclarationTextRange? = null,
    ): ValueSite {
        val result = read(site)
        val selected = result.transfers.filter { it.kind == kind && (expected == null || it.target.range == expected) }
        assertEquals(
            "The original source prefix needs $kind at ${site.range}. Causes=${result.obligations.map { it.cause }}",
            1,
            selected.size,
        )
        return selected.single().target
    }

    private fun seed(): ValueSite {
        val request =
            fixture.selection(
                PacketDeclaration(NativePacketCompositeSuccessSource.CLIENT, "OffersClientImpl", "readOffers"),
                "execute",
                PacketDeclaration(NativePacketCompositeSuccessSource.WIRE, "Call", "execute"),
            )
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("The exact original S2 producer must admit", result is ValueProducerSeedRead.Seeded)
        val site = (result as ValueProducerSeedRead.Seeded).seed.site
        assertEquals(request.anchor, site.range)
        return site
    }

    private fun exact(start: Int, end: Int) = ExactDeclarationTextRange.parse(start, end).nativeRefined()

    private companion object {
        const val INITIALIZER_START = 818
        const val CLIENT_END = 860
        const val INITIALIZER_END = 917
        const val FIRST_START = 939
        const val FIRST_END = 967
        const val THIS_START = 1622
        const val THIS_END = 1626
        const val LET_END = 1655
        const val ELVIS_END = 1681
        val FACTORY_ROUTE =
            listOf(
                ValueTransferKind.SOURCE_CALL_ENTRY,
                ValueTransferKind.LOCAL_READ,
                ValueTransferKind.ARGUMENT,
                ValueTransferKind.CONSTRUCTOR_FIELD,
                ValueTransferKind.RETURN,
                ValueTransferKind.SOURCE_CALL_RETURN,
            )
        val COMPONENT_ROUTE =
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
                ValueTransferKind.FIELD_READ,
                ValueTransferKind.RECEIVER,
            )
    }
}

internal data class CallerNonNullCase(
    val returned: ValueSite,
    val receiver: ValueSite,
    val checked: ValueSite,
    val letResult: ValueSite,
)
