package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalPositionDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import junit.framework.TestCase.assertTrue

/** Source prefixes and emitted exclusions stay separate from the pending collection-model assertion. */
internal class NativePacketKnownEmptyQueryOracle(private val facts: NativePacketKnownEmptyQueryFacts) {
    fun assertEmptySourcePrefixBeforeTheCollectionRule(paths: List<ImpactPathDocument>) {
        assertTrue(
            "Known-empty source lacks a contiguous route to the original firstOrNull operand",
            paths.any(::reachesOriginalCollectionOperand),
        )
        println("KNOWN_EMPTY_QUERY stage=source-prefix collection_rule=NOT_RUN cardinality=NOT_PROVEN")
    }

    fun assertOriginalRequestReachesTheRealKnownEmptyApiFormal(paths: List<ImpactPathDocument>) {
        val entries = paths.filter { it.compositeContiguous() }.flatMap { it.compilerTransfers() }
        assertTrue(
            "The original request did not enter the source-defined empty API body formal",
            entries.any(::entersKnownEmptyApi),
        )
    }

    fun assertNoOriginalRequestFeatureLinkWitness(paths: List<ImpactPathDocument>) {
        val links =
            paths
                .flatMap { it.compositeSites() }
                .filter { site ->
                    ImpactValuePathSegmentDocument.Field(facts.featureLink) in site.valuePath.values
                }
        assertTrue("The original request acquired FeatureResponse.link at ${links.map { it.range }}", links.isEmpty())
    }

    private fun reachesOriginalCollectionOperand(path: ImpactPathDocument): Boolean {
        if (!path.compositeContiguous() || path.producer != facts.knownEmptyOrigin) return false
        val route = path.compilerTransfers()
        val wireRead = route.indexOfFirst { reads(it, facts.wireLinks) }
        if (wireRead < 0) return false
        val storage = route.indices.firstOrNull { it > wireRead && storesClientLinks(route[it]) } ?: return false
        return route.drop(storage + 1).any { reads(it, facts.clientLinks) && isOriginalOperand(it.target) }
    }

    private fun storesClientLinks(transfer: ImpactCompilerTransferDocument): Boolean {
        val storage =
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage ?: return false
        if (
            transfer.kind != ImpactTransferKindDocument.CONSTRUCTOR_FIELD ||
                storage.constructor != facts.clientConstructor ||
                storage.parameter.callable != facts.clientConstructor
        )
            return false
        val exactField = storage.field == facts.clientLinks && storage.parameter.range == facts.clientLinks.range
        val exactParameter = storage.parameter.index.value == 0 && storage.parameter.name == packetQueryText("links")
        return exactField && exactParameter
    }

    private fun entersKnownEmptyApi(transfer: ImpactCompilerTransferDocument): Boolean {
        val entered = transfer.compositeOperation() as? ImpactSourceOperationDocument.Enter ?: return false
        val formal = (entered.position as? ImpactFormalPositionDocument.Parameter)?.parameter ?: return false
        if (transfer.kind != ImpactTransferKindDocument.SOURCE_CALL_ENTRY) return false
        val exactFormal = formal.callable == facts.emptyApiMethod && formal.range == facts.emptyApiBodyParameter
        val exactPosition = formal.index.value == 1 && formal.name == packetQueryText("body")
        return facts.apiFrame.matches(entered.frame) && exactFormal && exactPosition
    }

    private fun isOriginalOperand(site: ImpactValueSiteReferenceDocument): Boolean {
        val owner = site.executionOwner as? ImpactExecutionOwnerDocument.Anonymous ?: return false
        val exactSite = site.enclosing == facts.featureGetOffer && site.range == facts.firstOrNullOperand
        return exactSite && owner.body.range == facts.sequentialBody
    }

    private fun reads(transfer: ImpactCompilerTransferDocument, field: ImpactDeclarationReferenceDocument) =
        transfer.kind == ImpactTransferKindDocument.FIELD_READ &&
            (transfer.compositeOperation() as? ImpactSourceOperationDocument.ReadField)?.field == field

    private fun ImpactPathDocument.compilerTransfers() =
        steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }
}

/** The existing source adapter must supply these facts before either native capture. */
internal data class NativePacketKnownEmptyQueryFacts(
    val knownEmptyOrigin: ImpactValueSiteReferenceDocument,
    val wireLinks: ImpactDeclarationReferenceDocument,
    val clientLinks: ImpactDeclarationReferenceDocument,
    val clientConstructor: ImpactDeclarationReferenceDocument,
    val featureLink: ImpactDeclarationReferenceDocument,
    val featureGetOffer: ImpactDeclarationReferenceDocument,
    val firstOrNullOperand: ImpactSourceRangeDocument,
    val sequentialBody: ImpactSourceRangeDocument,
    val apiFrame: NativePacketQueryFrameOracle,
    val emptyApiMethod: ImpactDeclarationReferenceDocument,
    val emptyApiBodyParameter: ImpactSourceRangeDocument,
)
