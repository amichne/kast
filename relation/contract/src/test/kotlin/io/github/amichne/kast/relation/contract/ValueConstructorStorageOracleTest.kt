package io.github.amichne.kast.relation.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateName
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ValueConstructorStorageOracleTest {
    @Test
    fun `constructor storage binds the exact primary property parameter`() {
        val fixture = constructorCase()
        assertSame(fixture.parameter, fixture.storage.parameter)
        assertSame(fixture.field, fixture.storage.field)
        assertEquals(1, fixture.storage.parameter.position.value)
        assertEquals(fixture.parameter.range, fixture.field.declaration.range)
        assertEquals(fixture.parameter.name, fixture.field.declaration.name)
        assertEquals(fixture.constructor.file, fixture.field.declaration.file)
    }

    @Test
    fun `constructor storage rejects a sibling property or another primary parameter`() {
        val fixture = constructorCase()
        val anotherParameter =
            ValueFormalParameter.fromCompiler(
                    fixture.constructor,
                    ValueArgumentPosition.parse(0).flowRefined(),
                    SymbolDiscoveryCandidateName.parse("channel").flowRefined(),
                    flowRange(305, 315),
                )
                .flowRefined()
        assertEquals(
            Refinement.Rejected(ValueConstructorStorageFailure.PROPERTY_PARAMETER_MISMATCH),
            ValueConstructorStorage.fromCompiler(anotherParameter, fixture.field),
        )
        for (field in
            listOf(
                flowField("WireRequest.sessionToken", 350, 370),
                flowField("WireRequest.sessionToken", 320, 340),
                otherFileField(fixture.field),
            )) {
            assertEquals(
                Refinement.Rejected(ValueConstructorStorageFailure.PROPERTY_PARAMETER_MISMATCH),
                ValueConstructorStorage.fromCompiler(fixture.parameter, field),
            )
        }
    }

    @Test
    fun `constructor storage rejects a foreign evidence basis`() {
        val fixture = constructorCase()
        assertEquals(
            Refinement.Rejected(ValueConstructorStorageFailure.BASIS_MISMATCH),
            ValueConstructorStorage.fromCompiler(
                fixture.parameter,
                flowField("WireRequest.destination", 320, 340, generation = 2),
            ),
        )
    }

    @Test
    fun `constructor storage adds the exact field path and preserves the caller context`() {
        val fixture = constructorCase()
        val path = flowPath(ValuePathSegment.Field(fixture.field))
        val argument =
            ValueSite.fromCompiler(
                    fixture.caller,
                    flowRange(30, 40),
                    ValueRole.Argument(fixture.call, ValueArgumentPosition.parse(1).flowRefined()),
                )
                .flowRefined()
        val result = fixture.call.resultSite().withState(valuePath = path).flowRefined()
        val evidence =
            ValueSourceTransfer.fromCompiler(ValueSourceOperation.ConstructField(fixture.call, fixture.storage))
        val constructed =
            ValueTransfer.fromCompiler(argument, result, ValueTransferKind.CONSTRUCTOR_FIELD, evidence).flowRefined()
        assertEquals(listOf(ValuePathSegment.Field(fixture.field)), constructed.target.valuePath.segments)
        assertEquals(argument.callStack, constructed.target.callStack)
    }

    @Test
    fun `constructor storage cannot substitute another formal index`() {
        val fixture = constructorCase()
        val result =
            fixture.call
                .resultSite()
                .withState(valuePath = flowPath(ValuePathSegment.Field(fixture.field)))
                .flowRefined()
        val evidence =
            ValueSourceTransfer.fromCompiler(ValueSourceOperation.ConstructField(fixture.call, fixture.storage))
        val wrongIndex =
            ValueSite.fromCompiler(
                    fixture.caller,
                    flowRange(30, 40),
                    ValueRole.Argument(fixture.call, ValueArgumentPosition.parse(0).flowRefined()),
                )
                .flowRefined()
        rejectConstructorTransfer(wrongIndex, result, ValueTransferKind.CONSTRUCTOR_FIELD, evidence)
    }

    @Test
    fun `constructor storage cannot substitute another callable`() {
        val fixture = constructorCase()
        val path = flowPath(ValuePathSegment.Field(fixture.field))
        val otherConstructor = flowConstructor("OtherRequest.<init>", 400, 500)
        val otherCall = ValueInvocation.fromCompiler(fixture.caller, flowRange(70, 110), otherConstructor).flowRefined()
        val otherArgument =
            ValueSite.fromCompiler(
                    fixture.caller,
                    flowRange(80, 90),
                    ValueRole.Argument(otherCall, ValueArgumentPosition.parse(1).flowRefined()),
                )
                .flowRefined()
        rejectConstructorTransfer(
            otherArgument,
            otherCall.resultSite().withState(valuePath = path).flowRefined(),
            ValueTransferKind.CONSTRUCTOR_FIELD,
            ValueSourceTransfer.fromCompiler(ValueSourceOperation.ConstructField(otherCall, fixture.storage)),
        )
    }

    private fun rejectConstructorTransfer(
        source: ValueSite,
        target: ValueSite,
        kind: ValueTransferKind,
        evidence: ValueTransferEvidence,
    ) {
        assertEquals(
            Refinement.Rejected(ValueTransferFailure.EVIDENCE_MISMATCH),
            ValueTransfer.fromCompiler(source, target, kind, evidence),
        )
    }
}

private fun constructorCase(): ConstructorCase {
    val caller = flowEndpoint("construct", 0, 200)
    val constructor = flowConstructor("WireRequest.<init>", 300, 400)
    val call = ValueInvocation.fromCompiler(caller, flowRange(20, 60), constructor).flowRefined()
    val field = flowField("WireRequest.destination", 320, 340)
    val parameter =
        ValueFormalParameter.fromCompiler(
                constructor,
                ValueArgumentPosition.parse(1).flowRefined(),
                SymbolDiscoveryCandidateName.parse("destination").flowRefined(),
                flowRange(320, 340),
            )
            .flowRefined()
    return ConstructorCase(
        caller,
        constructor,
        call,
        field,
        parameter,
        ValueConstructorStorage.fromCompiler(parameter, field).flowRefined(),
    )
}

private data class ConstructorCase(
    val caller: RelationEndpoint,
    val constructor: RelationEndpoint,
    val call: ValueInvocation,
    val field: ValueField,
    val parameter: ValueFormalParameter,
    val storage: ValueConstructorStorage,
)

private fun flowConstructor(name: String, start: Int, end: Int): RelationEndpoint {
    val declaration =
        flowEndpoint(name, start, end, parameters = listOf("String", "String")) as RelationEndpoint.Resolved
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                declaration.file,
                start,
                end,
                declaration.name.value,
                "fixture.$name",
                CompilerSymbolKind.CONSTRUCTOR,
                declaration.signature,
            )
            .flowRefined()
    return RelationEndpoint.resolve(declaration.lease, declaration.scope, evidence).flowRefined()
}

private fun otherFileField(field: ValueField): ValueField {
    val declaration = field.declaration
    val file =
        (SymbolDiscoveryCandidate.fromBoundary(
                    SymbolDiscoveryKind.SYMBOL,
                    declaration.name.value,
                    declaration.lease,
                    Path.of("/fixture/Other.kt"),
                    "file:///fixture/Other.kt",
                    declaration.range.startInclusive,
                )
                .flowRefined()
                .location as SymbolDiscoveryCandidateLocation.Declaration)
            .file
    val evidence =
        CompilerGroundedSymbolEvidence.fromBoundary(
                file,
                declaration.range.startInclusive,
                declaration.range.endExclusive,
                declaration.name.value,
                "fixture.WireRequest.destination",
                CompilerSymbolKind.PROPERTY,
                declaration.signature,
            )
            .flowRefined()
    return ValueField.fromCompiler(
            RelationEndpoint.resolve(declaration.lease, declaration.scope, evidence).flowRefined()
        )
        .flowRefined()
}
