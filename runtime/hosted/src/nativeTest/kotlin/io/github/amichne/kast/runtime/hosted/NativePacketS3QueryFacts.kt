package io.github.amichne.kast.runtime.hosted

import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument

/** The original source and exact-symbol port supply every expectation before the S3 query starts. */
internal data class NativePacketS3QueryFacts(
    val origin: ImpactValueSiteReferenceDocument,
    val property: ImpactDeclarationReferenceDocument,
    val client: ImpactDeclarationReferenceDocument,
    val logBody: ImpactExecutionOwnerDocument,
    val logParameter: ImpactSourceRangeDocument,
    val helper: ImpactDeclarationReferenceDocument,
    val helperInvocation: ImpactInvocationReferenceDocument,
    val helperReturn: ImpactSourceRangeDocument,
    val loggerArgument: ImpactValueSiteReferenceDocument,
    val executeReturn: ImpactValueSiteReferenceDocument,
    val callerResult: ImpactValueSiteReferenceDocument,
    val callerFrame: NativePacketQueryFrameOracle,
    val success: S3LogBranchFacts,
    val error: S3LogBranchFacts,
    val api: ImpactDeclarationReferenceDocument,
)

internal data class S3LogBranchFacts(
    val type: ImpactDeclarationReferenceDocument,
    val carrierField: ImpactDeclarationReferenceDocument,
    val payloadField: ImpactDeclarationReferenceDocument,
    val template: ImpactSourceRangeDocument,
)

internal enum class S3LogBranch {
    SUCCESS,
    ERROR,
}

internal suspend fun NativePacketCompositeQueryCase.s3Facts(): NativePacketS3QueryFacts {
    val execute = source.reference(source.declaration(WIRE, "Call", "execute"))
    val clientSelector = source.declaration(CLIENT, "OffersClientImpl", "readOffers")
    val client = source.reference(clientSelector)
    val helper = source.reference(source.declaration(CLIENT, "", "toLogLine"))
    val logger = source.reference(source.declaration(WIRE, "Logger", "info"))
    val body = source.anonymousOwner(clientSelector, source.logBody())
    val caller = ImpactInvocationReferenceDocument(source.s3ClientCall("execute"), execute)
    val origin =
        ImpactValueSiteReferenceDocument(
            execute,
            source.s3ExecuteCall("adapt"),
            ImpactValueRoleDocument.ExpressionResult,
        )
    val property = source.field(WIRE, "Call", "adapt")
    check(origin.range == NativePacketS3QueryRanges.adapt)
    check(property.range == NativePacketS3QueryRanges.property)
    return NativePacketS3QueryFacts(
        origin = origin,
        property = property,
        client = client,
        logBody = body,
        logParameter = source.parameterRange(path = WIRE, owner = "Call", method = "execute", index = 0),
        helper = helper,
        helperInvocation = ImpactInvocationReferenceDocument(NativePacketS3QueryRanges.logHelper, helper, body),
        helperReturn = source.returnRange(CLIENT, "", "toLogLine"),
        loggerArgument = source.s3LoggerArgument(execute, logger),
        executeReturn =
            ImpactValueSiteReferenceDocument(
                execute,
                source.returnOperand(WIRE, "Call", "execute"),
                ImpactValueRoleDocument.Return,
            ),
        callerResult = ImpactValueSiteReferenceDocument(client, caller.range, ImpactValueRoleDocument.ExpressionResult),
        callerFrame =
            NativePacketQueryFrameOracle(
                enclosing = client,
                invocation = caller,
                callee = ImpactExecutionReferenceDocument(execute),
                ownership = null,
            ),
        success = s3Branch(carrier = "Success", path = WIRE, payloadOwner = "Envelope", payloadField = "statusCode"),
        error = s3Branch(carrier = "Error", path = WIRE, payloadOwner = "WireError", payloadField = "code"),
        api = source.reference(source.declaration(WIRE, "OffersApi", "readOffers")),
    )
}

private suspend fun NativePacketCompositeQueryCase.s3Branch(
    carrier: String,
    path: String,
    payloadOwner: String,
    payloadField: String,
) =
    S3LogBranchFacts(
        type = source.type(OUTCOME, carrier),
        carrierField = source.field(OUTCOME, carrier, "value"),
        payloadField = source.field(path, payloadOwner, payloadField),
        template = source.logTemplate(payloadField),
    )

private fun NativePacketCompositeQuerySource.s3ExecuteCall(callee: String) =
    invocation(path = WIRE, owner = "Call", method = "execute", callee = callee)

private fun NativePacketCompositeQuerySource.s3ClientCall(callee: String) =
    invocation(path = CLIENT, owner = "OffersClientImpl", method = "readOffers", callee = callee)

private fun NativePacketCompositeQuerySource.s3LoggerArgument(
    execute: ImpactDeclarationReferenceDocument,
    logger: ImpactDeclarationReferenceDocument,
) =
    ImpactValueSiteReferenceDocument(
        execute,
        s3ExecuteCall("log"),
        ImpactValueRoleDocument.Argument(
            ImpactInvocationReferenceDocument(s3ExecuteCall("info"), logger),
            packetQueryOffset(0),
        ),
    )

private const val WIRE = NativePacketCompositeQuerySource.WIRE
private const val CLIENT = NativePacketCompositeQuerySource.CLIENT
private const val OUTCOME = NativePacketCompositeQuerySource.OUTCOME
