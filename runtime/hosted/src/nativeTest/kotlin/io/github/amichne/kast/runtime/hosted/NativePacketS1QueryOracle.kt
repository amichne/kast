package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactValuePath
import io.github.amichne.kast.protocol.contract.ImpactCallOccurrenceDocument
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import junit.framework.TestCase.assertTrue

/** Required request arrivals and forbidden emitted witnesses use separate original-S1 assertions. */
internal class NativePacketS1QueryOracle(private val facts: NativePacketS1QueryFacts) {
    fun assertOriginalRequestRowsThroughC14(paths: List<ImpactPathDocument>) {
        val rows = paths.map(::rows)
        assertTrue(
            "Original constructor707:792 lacks one complete C1.1-C1.4 retained route: $rows",
            rows.any { it.all { row -> row.passed } },
        )
    }

    fun assertProductionForbiddenWitnesses(paths: List<ImpactPathDocument>) {
        assertTrue(
            "Forbidden-witness assessment needs admitted original-S1 evidence",
            paths.any { it.producer == facts.origin },
        )
        val sites = paths.flatMap { it.compositeSites() }
        val responseFields = setOf(facts.common.clientLinks, facts.common.featureLink)
        val responses = sites.filter { site ->
            site.valuePath.values.any { segment ->
                segment is ImpactValuePathSegmentDocument.Field && segment.declaration in responseFields
            }
        }
        assertTrue("C1.N1 emitted request-to-response fields at ${responses.map { it.range }}", responses.isEmpty())
        val results = sites.filter { site ->
            site.enclosing == facts.common.mapper ||
                site.range == facts.common.featureResult ||
                site.enclosing == facts.common.getOfferReturn.enclosing &&
                    site.role == ImpactValueRoleDocument.Return &&
                    site.range == facts.common.getOfferReturn.range
        }
        assertTrue("C1.N1 emitted a production response result or getOffer return", results.isEmpty())
        assertNoLoggerArguments(paths)
        val borrowed = paths.filter { path ->
            val route = path.transfers()
            route.any { (it.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field == facts.token } &&
                route.any { transfer ->
                    (transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage?.field ==
                        facts.wireDestination
                }
        }
        assertTrue("sessionToken acquired WireRequest.destination", borrowed.isEmpty())
        val channel = sites.filter { ImpactValuePathSegmentDocument.Field(facts.wireChannel) in it.valuePath.values }
        assertTrue("S1 input acquired the independent constant channel", channel.isEmpty())
    }

    fun assertNoLoggerArguments(paths: List<ImpactPathDocument>) {
        val loggers = setOf(facts.loggerInfo, facts.loggerWarn)
        val emitted =
            paths
                .flatMap { it.compositeSites() }
                .filter { site ->
                    (site.role as? ImpactValueRoleDocument.Argument)?.invocation?.callable in loggers
                }
        assertTrue("C1.N2 emitted request provenance at a Logger argument", emitted.isEmpty())
    }

    private fun rows(path: ImpactPathDocument): List<CompositeRequiredRow> {
        if (path.producer != facts.origin || !path.compositeContiguous())
            return listOf(CompositeRequiredRow("S1_ORIGINAL", false))
        val route = S1RetainedRows(path.transfers())
        val first = route.row("C1.1", ::requestArgument)
        val entered = route.row("C1.2", ::productionFormal)
        val mapper = route.row("C1.3:extension-entry", ::mapperEntry)
        val field =
            route.row("C1.3:destination-read") { transfer ->
                transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
                    transfer.target.enclosing == facts.mapper &&
                    (transfer.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field ==
                        facts.destination
            }
        val stored = route.row("C1.3:WireRequest.destination", ::storesDestination)
        val returned = route.row("C1.3:matched-mapper-return", ::mapperReturn)
        val argument = route.row("C1.4:body[1]", ::bodyArgument)
        val terminal = path.terminal as? ImpactPathTerminalDocument.UnresolvedFlow
        val opaque = terminal != null && opaqueTerminal(terminal)
        return listOf(
            first,
            entered,
            mapper,
            field,
            stored,
            returned,
            argument,
            CompositeRequiredRow("C1.4:opaque-qualification", opaque),
        )
    }

    private fun requestArgument(transfer: ImpactCompilerTransferDocument) =
        transfer.kind == ImpactTransferKindDocument.ARGUMENT &&
            transfer.target.range == facts.requestArgument &&
            transfer.target.role ==
                ImpactValueRoleDocument.Argument(facts.common.callerFrame.invocation, packetQueryOffset(0)) &&
            transfer.target.executionOwner == facts.origin.executionOwner &&
            transfer.target.valuePath == EmptyImpactValuePath

    private fun bodyArgument(transfer: ImpactCompilerTransferDocument) =
        transfer.kind == ImpactTransferKindDocument.ARGUMENT &&
            transfer.target.range == facts.apiBody.range &&
            transfer.target.role == facts.apiBody.role &&
            transfer.target.enclosing == facts.apiBody.enclosing &&
            transfer.target.valuePath.values == listOf(ImpactValuePathSegmentDocument.Field(facts.wireDestination))

    private fun productionFormal(transfer: ImpactCompilerTransferDocument): Boolean {
        val operation = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
            facts.common.callerFrame.matches(operation.frame) &&
            operation.position == ImpactFormalPositionDocument.Parameter(facts.requestFormal) &&
            transfer.target.valuePath == EmptyImpactValuePath
    }

    private fun mapperEntry(transfer: ImpactCompilerTransferDocument): Boolean {
        val entry = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
            entry.frame.invocation == ImpactCallOccurrenceDocument.Declared(facts.mapperInvocation) &&
            entry.frame.callee.enclosing == facts.mapper &&
            entry.position == ImpactFormalPositionDocument.Receiver(ImpactReceiverKindDocument.EXTENSION)
    }

    private fun storesDestination(transfer: ImpactCompilerTransferDocument): Boolean {
        val field =
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage ?: return false
        return transfer.kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
            transfer.target.enclosing == facts.mapper &&
            field.constructor == facts.common.wireConstructor &&
            field.field == facts.wireDestination &&
            field.parameter.callable == facts.common.wireConstructor &&
            field.parameter.index.value == 0 &&
            field.parameter.name == packetQueryText("destination") &&
            field.parameter.range == facts.wireDestination.range
    }

    private fun opaqueTerminal(terminal: ImpactPathTerminalDocument.UnresolvedFlow) =
        terminal.site.enclosing == facts.apiBody.enclosing &&
            terminal.site.range == facts.apiBody.range &&
            terminal.site.role == facts.apiBody.role &&
            terminal.site.valuePath.values == listOf(ImpactValuePathSegmentDocument.Field(facts.wireDestination)) &&
            terminal.cause == ImpactFlowUnsupportedDocument.EXTERNAL_CALL

    private fun mapperReturn(transfer: ImpactCompilerTransferDocument): Boolean {
        val returned = transfer.compositeOperation() as? ImpactSourceOperationDocument.Return ?: return false
        return transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
            returned.frame.invocation == ImpactCallOccurrenceDocument.Declared(facts.mapperInvocation) &&
            returned.frame.callee.enclosing == facts.mapper &&
            transfer.source.role == ImpactValueRoleDocument.Return &&
            transfer.source.range == facts.mapperReturn &&
            transfer.target.range == facts.mapperInvocation.range &&
            transfer.target.valuePath.values == listOf(ImpactValuePathSegmentDocument.Field(facts.wireDestination))
    }

    private fun ImpactPathDocument.transfers() =
        steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
}

private class S1RetainedRows(private val route: List<ImpactCompilerTransferDocument>) {
    private var cursor = 0
    private var blocked = false

    fun row(id: String, matches: (ImpactCompilerTransferDocument) -> Boolean): CompositeRequiredRow {
        if (blocked) return CompositeRequiredRow("$id:NOT_REACHED", false)
        val found = route.indices.firstOrNull { it >= cursor && matches(route[it]) }
        if (found == null) {
            blocked = true
            return CompositeRequiredRow("$id:MISSING", false)
        }
        cursor = found + 1
        return CompositeRequiredRow(id, true)
    }
}
