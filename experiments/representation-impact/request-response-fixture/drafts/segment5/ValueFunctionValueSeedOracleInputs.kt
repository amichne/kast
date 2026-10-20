package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange

/** These detached inputs await the shared origin API. They do not claim native reference resolution. */
internal class ValueFunctionValueSeedOracleInputs {
    val host = flowEndpoint("Call.execute", 0, 300)
    val property = functionProperty("Call.adapt", 400, 450)
    val otherProperty = functionProperty("Call.otherAdapt", 500, 550)
    val reference = flowRange(20, 25)
    val invocation = functionCall(20, 27)
    val secondInvocation = functionCall(50, 57)
    val sourceMethod = flowEndpoint("Call.otherExecute", 600, 650)
    val foreignProperty = functionProperty(name = "Call.adapt", start = 400, end = 450, generation = 2)

    val cases =
        listOf(
            FunctionValueOriginInput(
                case = "exact property origin",
                invocation = invocation,
                property = property,
                reference = reference,
                expected = FunctionValueOriginOutcome.ADMITTED,
            ),
            FunctionValueOriginInput(
                case = "second actual invocation",
                invocation = secondInvocation,
                property = property,
                reference = flowRange(50, 55),
                expected = FunctionValueOriginOutcome.ADMITTED,
            ),
            FunctionValueOriginInput(
                case = "foreign property basis",
                invocation = invocation,
                property = foreignProperty,
                reference = reference,
                expected = FunctionValueOriginOutcome.BASIS_REJECTED,
            ),
            FunctionValueOriginInput(
                case = "named source method",
                invocation = invocation,
                property = sourceMethod,
                reference = reference,
                expected = FunctionValueOriginOutcome.PROPERTY_REJECTED,
            ),
            FunctionValueOriginInput(
                case = "reference after invocation",
                invocation = invocation,
                property = property,
                reference = flowRange(28, 33),
                expected = FunctionValueOriginOutcome.REFERENCE_REJECTED,
            ),
            FunctionValueOriginInput(
                case = "reference from first invocation",
                invocation = secondInvocation,
                property = property,
                reference = reference,
                expected = FunctionValueOriginOutcome.REFERENCE_REJECTED,
            ),
        )

    val seedCases =
        listOf(
            FunctionValueSeedInput(
                case = "same invocation and requested property",
                actualInvocation = invocation,
                originInvocation = invocation,
                originProperty = property,
                requestedProperty = property,
                expected = FunctionValueSeedOutcome.ADMITTED,
            ),
            FunctionValueSeedInput(
                case = "wrong requested property of the same type",
                actualInvocation = invocation,
                originInvocation = invocation,
                originProperty = property,
                requestedProperty = otherProperty,
                expected = FunctionValueSeedOutcome.EXPECTED_PROPERTY_REJECTED,
            ),
            FunctionValueSeedInput(
                case = "origin belongs to the other invocation",
                actualInvocation = invocation,
                originInvocation = secondInvocation,
                originProperty = property,
                requestedProperty = property,
                expected = FunctionValueSeedOutcome.INVOCATION_REJECTED,
            ),
        )

    fun request(
        budget: RelationBudget,
        expected: RelationEndpoint = property,
        anchor: ExactDeclarationTextRange = invocation.range,
    ) =
        ValueProducerSeedRequest.create(
                enclosing = selector(host),
                anchor = anchor,
                expectedCallable = selector(expected),
                budget = budget,
                boundary = RelationSearchBoundary.RETAINED_SUBJECT,
            )
            .flowRefined()

    private fun functionCall(start: Int, end: Int) =
        ValueFunctionInvocation.fromCompiler(host, flowRange(start, end), builtin()).flowRefined()

    private fun functionProperty(name: String, start: Int, end: Int, generation: Long = 1) =
        flowEndpoint(
            name = name,
            start = start,
            end = end,
            generation = generation,
            propertyType = "() -> Outcome<Error, Envelope<Response>>",
        )

    private fun builtin(): SourceLessCallable {
        val prototype = callbackOracleBuiltin()
        val signature =
            CanonicalCompilerSignature.function(
                    rawQualifiedIdentity = "kotlin.Function0.invoke",
                    rawReceiverType = null,
                    rawContextReceiverTypes = emptyList(),
                    rawValueParameterTypes = emptyList(),
                    rawTypeParameterCount = 0,
                )
                .flowRefined()
        return SourceLessCallable.fromCompiler(
                signature = signature,
                kind = prototype.kind,
                origin = SourceLessCallableOrigin.LIBRARY,
                moduleKind = SourceLessCallableModuleKind.BUILTINS,
                moduleName = prototype.moduleName,
            )
            .flowRefined()
    }

    private fun selector(endpoint: RelationEndpoint) =
        io.github.amichne.kast.symbol.contract.SymbolSelector.issue(
            endpoint.lease,
            endpoint.scope,
            (endpoint as RelationEndpoint.Resolved).evidence,
        )
}

internal enum class FunctionValueOriginOutcome {
    ADMITTED,
    BASIS_REJECTED,
    PROPERTY_REJECTED,
    REFERENCE_REJECTED,
}

internal data class FunctionValueOriginInput(
    val case: String,
    val invocation: ValueFunctionInvocation,
    val property: RelationEndpoint,
    val reference: ExactDeclarationTextRange,
    val expected: FunctionValueOriginOutcome,
)

internal enum class FunctionValueSeedOutcome {
    ADMITTED,
    EXPECTED_PROPERTY_REJECTED,
    INVOCATION_REJECTED,
}

internal data class FunctionValueSeedInput(
    val case: String,
    val actualInvocation: ValueFunctionInvocation,
    val originInvocation: ValueFunctionInvocation,
    val originProperty: RelationEndpoint,
    val requestedProperty: RelationEndpoint,
    val expected: FunctionValueSeedOutcome,
)
