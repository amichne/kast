package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.EmptyImpactCallStack
import io.github.amichne.kast.protocol.contract.ImpactCompilerTransferDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFlowUnsupportedDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOperationDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferEvidenceDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.ProtocolOffset
import io.github.amichne.kast.protocol.contract.hasSameCallStackIdentity

/** Expectations come from the frozen program and source-resolved declarations, independent of the flow adapter. */
internal class NativePacketQueryOracle
private constructor(
    val outgoingOccurrence: ImpactValueSiteReferenceDocument,
    val incomingOccurrence: ImpactValueSiteReferenceDocument,
    private val fetchFrame: NativePacketQueryFrameOracle,
    private val fields: PacketQueryFields,
    private val requestConstructors: Set<ImpactDeclarationReferenceDocument>,
    private val helpers: NativePacketQueryHelperOracle?,
) {
    private val expectedOutgoing =
        outgoingOccurrence.copy(
            valuePath = packetQueryList(listOf(ImpactValuePathSegmentDocument.Field(fields.wireDestination)))
        )
    private val expectedIncoming =
        incomingOccurrence.copy(
            valuePath = packetQueryList(listOf(ImpactValuePathSegmentDocument.Field(fields.featureLink)))
        )

    fun outgoingRoute(paths: List<ImpactPathDocument>): Boolean = paths.any { path ->
        val route = path.compilerRoute()
        if (!path.hasContiguousRoute(route) || route.none { matchesOutgoing(it.target) }) return@any false
        if (route.none { it.enters(fetchFrame) } || route.none { it.reads(fields.clientDestination) }) return@any false
        route.any { it.storesWireDestination() } &&
            path.endsAtOpaqueSend() &&
            helpers?.outgoing(route, fields.clientDestination, fields.wireDestination) != false
    }

    fun incomingRoute(paths: List<ImpactPathDocument>): Boolean = paths.any { path ->
        val route = path.compilerRoute()
        if (!path.hasContiguousRoute(route) || route.none { it.target == expectedIncoming }) return@any false
        if (route.none { it.reads(fields.wireLink) } || route.none { it.stores(fields.clientLink) }) return@any false
        if (helpers?.incoming(route, fields.wireLink, fields.clientLink) == false) return@any false
        route.any { it.matchesFetchReturn() } &&
            route.any { it.reads(fields.clientLink) } &&
            route.any { it.stores(fields.featureLink) }
    }

    fun outgoingForbidden(paths: List<ImpactPathDocument>): List<ImpactValueSiteReferenceDocument> =
        paths
            .flatMap { it.emittedSites() }
            .filter { site ->
                site.enclosing == incomingOccurrence.enclosing &&
                    site.range == incomingOccurrence.range &&
                    site.role == ImpactValueRoleDocument.Return
            }

    fun incomingForbidden(paths: List<ImpactPathDocument>): List<ImpactValueSiteReferenceDocument> =
        paths
            .flatMap { it.emittedSites() }
            .filter { site ->
                val role = site.role
                role is ImpactValueRoleDocument.Argument &&
                    (role.invocation.callable ==
                        (outgoingOccurrence.role as ImpactValueRoleDocument.Argument).invocation.callable ||
                        role.invocation.callable in requestConstructors)
            }

    fun describe(paths: List<ImpactPathDocument>) = paths.map { path ->
        "steps=${path.compilerRoute().map { it.kind }} terminal=${path.terminal}"
    }

    private fun ImpactPathDocument.endsAtOpaqueSend(): Boolean {
        val unresolved = terminal as? ImpactPathTerminalDocument.UnresolvedFlow ?: return false
        return matchesOutgoing(unresolved.site) && unresolved.cause == ImpactFlowUnsupportedDocument.UNMODELED_CALL
    }

    private fun matchesOutgoing(site: ImpactValueSiteReferenceDocument): Boolean =
        site.copy(callStack = EmptyImpactCallStack) == expectedOutgoing &&
            site.callStack.values.size == 1 &&
            fetchFrame.matches(site.callStack.values.single())

    private fun ImpactCompilerTransferDocument.reads(field: ImpactDeclarationReferenceDocument): Boolean =
        kind == ImpactTransferKindDocument.FIELD_READ &&
            (sourceOperation() as? ImpactSourceOperationDocument.ReadField)?.field == field

    private fun ImpactCompilerTransferDocument.enters(frame: NativePacketQueryFrameOracle): Boolean =
        kind == ImpactTransferKindDocument.SOURCE_CALL_ENTRY &&
            (sourceOperation() as? ImpactSourceOperationDocument.Enter)?.frame?.let(frame::matches) == true

    private fun ImpactCompilerTransferDocument.stores(field: ImpactDeclarationReferenceDocument): Boolean =
        kind == ImpactTransferKindDocument.CONSTRUCTOR_FIELD &&
            (sourceOperation() as? ImpactSourceOperationDocument.ConstructField)?.storage?.field == field

    private fun ImpactCompilerTransferDocument.storesWireDestination(): Boolean {
        if (!stores(fields.wireDestination)) return false
        val actual = source.role as? ImpactValueRoleDocument.Argument ?: return false
        val storage = (sourceOperation() as ImpactSourceOperationDocument.ConstructField).storage
        if (actual.invocation.callable != fields.wireConstructor || actual.index != fields.wireDestinationIndex)
            return false
        if (
            storage.parameter.callable != fields.wireConstructor ||
                storage.parameter.index != fields.wireDestinationIndex
        )
            return false
        return storage.parameter.name == packetQueryText("destination") &&
            storage.parameter.range == fields.wireDestination.range
    }

    private fun ImpactCompilerTransferDocument.matchesFetchReturn(): Boolean {
        val operation = sourceOperation()
        val frame =
            when (operation) {
                is ImpactSourceOperationDocument.Return -> operation.frame
                is ImpactSourceOperationDocument.SeedReturn -> operation.frame
                else -> return false
            }
        if (kind != ImpactTransferKindDocument.SOURCE_CALL_RETURN || !fetchFrame.matches(frame)) return false
        if (source.enclosing != fetchFrame.callee.enclosing || target.enclosing != fetchFrame.enclosing) return false
        return target.range == fetchFrame.invocation.range && target.callStack.values.isEmpty()
    }

    companion object {
        suspend fun from(source: NativePacketQuerySource): NativePacketQueryOracle {
            val feature = source.reference(source.declaration("Feature", "handle"))
            val fetch = source.reference(source.declaration("BackendClient", "fetch"))
            val concrete = source.reference(source.declaration(source.variant.concreteClient, "fetch"))
            val frame =
                NativePacketQueryFrameOracle(
                    enclosing = feature,
                    invocation =
                        ImpactInvocationReferenceDocument(source.invocation("Feature", "handle", "fetch"), fetch),
                    callee = ImpactExecutionReferenceDocument(concrete),
                    ownership = if (source.variant == NativePacketQueryVariant.V1) source.ownership(concrete) else null,
                )
            return NativePacketQueryOracle(
                outgoingOccurrence = source.outgoingSite(),
                incomingOccurrence = source.featureSite(),
                fetchFrame = frame,
                fields = packetFields(source),
                requestConstructors =
                    setOf(
                        source.reference(source.declaration("ClientRequest", "<init>")),
                        source.reference(source.declaration("WireRequest", "<init>")),
                    ),
                helpers = NativePacketQueryHelperOracle.from(source),
            )
        }
    }
}

private data class PacketQueryFields(
    val clientDestination: ImpactDeclarationReferenceDocument,
    val wireDestination: ImpactDeclarationReferenceDocument,
    val wireLink: ImpactDeclarationReferenceDocument,
    val clientLink: ImpactDeclarationReferenceDocument,
    val featureLink: ImpactDeclarationReferenceDocument,
    val wireConstructor: ImpactDeclarationReferenceDocument,
    val wireDestinationIndex: ProtocolOffset,
)

private suspend fun packetFields(source: NativePacketQuerySource) =
    PacketQueryFields(
        clientDestination = source.field("ClientRequest", "destination"),
        wireDestination = source.field("WireRequest", "destination"),
        wireLink = source.field("WireResponse", "link"),
        clientLink = source.field("ClientResponse", "link"),
        featureLink = source.field("FeatureResponse", "link"),
        wireConstructor = source.reference(source.declaration("WireRequest", "<init>")),
        wireDestinationIndex = source.propertyIndex("WireRequest", "destination"),
    )

private fun ImpactCompilerTransferDocument.sourceOperation() =
    (evidence as? ImpactTransferEvidenceDocument.Source)?.operation

private fun ImpactPathDocument.compilerRoute() =
    steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer }

private fun ImpactPathDocument.hasContiguousRoute(route: List<ImpactCompilerTransferDocument>): Boolean {
    if (route.isEmpty() || route.size != steps.values.size) return false
    return producer == route.first().source &&
        route.zipWithNext().all { (first, second) ->
            first.target.copy(callStack = EmptyImpactCallStack) ==
                second.source.copy(callStack = EmptyImpactCallStack) &&
                first.target.callStack.hasSameCallStackIdentity(second.source.callStack)
        }
}

private fun ImpactPathDocument.emittedSites(): List<ImpactValueSiteReferenceDocument> =
    listOf(producer) +
        compilerRoute().flatMap { listOf(it.source, it.target) } +
        listOfNotNull((terminal as? ImpactPathTerminalDocument.UnresolvedFlow)?.site)
