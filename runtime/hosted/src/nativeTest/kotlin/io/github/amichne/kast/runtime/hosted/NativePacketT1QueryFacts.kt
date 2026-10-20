package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactFormalParameterDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactReceiverKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceKindDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceOwnershipDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceProvenanceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRootDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ModelOwnedSourceRoot
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance

internal data class NativePacketT1QueryFacts(
    val s1: NativePacketS1QueryFacts,
    val echo: ImpactDeclarationReferenceDocument,
    val echoFormal: ImpactFormalParameterDocument,
    val echoReturn: ImpactSourceRangeDocument,
    val echoList: ImpactSourceRangeDocument,
    val echoDestination: ImpactSourceRangeDocument,
    val echoConstructor: ImpactSourceRangeDocument,
    val mainOwnership: ImpactSourceOwnershipDocument,
    val testOwnership: ImpactSourceOwnershipDocument,
    val callerSuccessSubject: ImpactValueSiteReferenceDocument,
    val requireNotNull: ImpactDeclarationReferenceDocument,
    val requireInvocation: ImpactSourceRangeDocument,
)

internal suspend fun NativePacketCompositeQueryCase.t1Facts(): NativePacketT1QueryFacts {
    val s1 = s1Facts()
    val echo =
        source.reference(
            source.declaration(
                path = ECHO,
                owner = "EchoOffersClient",
                member = "readOffers",
                sourcePolicy = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
            )
        )
    val test = checkNotNull(testRoot) { "T1 needs the actual imported test root" }
    check(root.sourceSet.value == "main" && test.sourceSet.value == "test")
    check(root.sourceKind == WorkspaceSourceRootKind.PRODUCTION && test.sourceKind == WorkspaceSourceRootKind.TEST)
    check(
        root.provenance == WorkspaceSourceRootProvenance.AUTHORED &&
            test.provenance == WorkspaceSourceRootProvenance.AUTHORED
    )
    return NativePacketT1QueryFacts(
        s1 = s1,
        echo = echo,
        echoFormal = source.echoFormal(echo),
        echoReturn = source.returnRange(ECHO, "EchoOffersClient", "readOffers"),
        echoList = source.echoCall("listOf"),
        echoDestination =
            source.argumentRange(
                path = ECHO,
                owner = "EchoOffersClient",
                method = "readOffers",
                callee = "listOf",
                index = 0,
            ),
        echoConstructor = source.echoCall("ClientResponse"),
        mainOwnership = ownership(s1.common.clientMethod, root, ImpactSourceKindDocument.PRODUCTION),
        testOwnership = ownership(echo, test, ImpactSourceKindDocument.TEST),
        callerSuccessSubject = source.echoCallerSubject(s1),
        requireNotNull = source.reference(source.declaration(OUTCOME, "", "requireNotNull")),
        requireInvocation =
            source.invocation(
                path = FEATURE,
                owner = "OffersProcessorImpl",
                method = "getOffer",
                callee = "requireNotNull",
            ),
    )
}

private fun NativePacketCompositeQuerySource.echoFormal(echo: ImpactDeclarationReferenceDocument) =
    ImpactFormalParameterDocument(
        callable = echo,
        index = packetQueryOffset(0),
        name = packetQueryText("request"),
        range = parameterRange(path = ECHO, owner = "EchoOffersClient", method = "readOffers", index = 0),
    )

private fun NativePacketCompositeQuerySource.echoCall(callee: String) =
    invocation(path = ECHO, owner = "EchoOffersClient", method = "readOffers", callee = callee)

private suspend fun NativePacketCompositeQuerySource.echoCallerSubject(s1: NativePacketS1QueryFacts) =
    ImpactValueSiteReferenceDocument(
        enclosing = s1.origin.enclosing,
        range = s1.common.callerFrame.invocation.range,
        role =
            ImpactValueRoleDocument.Receiver(
                ImpactInvocationReferenceDocument(
                    range =
                        invocation(
                            path = FEATURE,
                            owner = "OffersProcessorImpl",
                            method = "getOffer",
                            callee = "mapError",
                        ),
                    callable = reference(declaration(OUTCOME, "Outcome", "mapError")),
                    executionOwner = s1.origin.executionOwner,
                ),
                ImpactReceiverKindDocument.DISPATCH,
            ),
        executionOwner = s1.origin.executionOwner,
    )

private fun ownership(
    declaration: ImpactDeclarationReferenceDocument,
    root: ModelOwnedSourceRoot,
    kind: ImpactSourceKindDocument,
) =
    ImpactSourceOwnershipDocument(
        declaration,
        ImpactSourceRootDocument(
            module = packetQueryText(root.module.value),
            buildRoot = packetQueryText(root.project.buildRoot.value),
            projectPath = packetQueryText(root.project.projectPath.value),
            sourceSet = packetQueryText(root.sourceSet.value),
            sourceRoot = packetQueryText(root.sourceRoot.value),
            sourceKind = kind,
            provenance = ImpactSourceProvenanceDocument.AUTHORED,
        ),
    )

private const val ECHO = "src/test/kotlin/fixture/client/EchoOffersClient.kt"
private const val FEATURE = NativePacketCompositeQuerySource.FEATURE
private const val OUTCOME = NativePacketCompositeQuerySource.OUTCOME
