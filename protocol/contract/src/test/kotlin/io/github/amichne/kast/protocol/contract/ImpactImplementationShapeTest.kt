package io.github.amichne.kast.protocol.contract

import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.WorkUnitLimit
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactImplementationShapeTest {
    @Test
    fun `implementation target encodes its full exact proof and direct default stays explicit`() {
        val f = ImplementationShapeFixture()
        assertTrue(f.frame.admits(f.basis))
        val encoded = Json.encodeToJsonElement(ImpactCallFrameDocument.serializer(), f.frame).jsonObject
        assertEquals(setOf("enclosing", "invocation", "callee", "target"), encoded.keys)
        val target = encoded.getValue("target").jsonObject
        assertEquals(setOf("type", "dispatch", "relation", "ownership"), target.keys)
        assertEquals("IMPLEMENTATION", target.getValue("type").jsonPrimitive.content)
        assertEquals(
            setOf(
                "question",
                "subjectFingerprint",
                "provider",
                "facts",
                "encodedBytes",
                "examinedWorkUnits",
                "resultCount",
                "omissions",
                "scopeExclusions",
                "coverage",
            ),
            target.getValue("dispatch").jsonObject.keys,
        )
        assertEquals(
            setOf("module", "buildRoot", "projectPath", "sourceSet", "sourceRoot", "sourceKind", "provenance"),
            target.getValue("ownership").jsonObject.getValue("root").jsonObject.keys,
        )
        val direct =
            ImpactCallFrameDocument(f.values.declaration, f.invocation, ImpactExecutionReferenceDocument(f.subject))
        assertTrue(direct.admits(f.basis))
        val default =
            Json.encodeToJsonElement(ImpactCallFrameDocument.serializer(), direct)
                .jsonObject
                .getValue("target")
                .jsonObject
        assertEquals(setOf("type"), default.keys)
        assertEquals("DIRECT", default.getValue("type").jsonPrimitive.content)
        val unknown =
            Json.encodeToString(ImpactCallFrameDocument.serializer(), f.frame)
                .replace("\"IMPLEMENTATION\"", "\"RUNTIME_CHOSEN\"")
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(ImpactCallFrameDocument.serializer(), unknown)
        }
    }

    @Test
    fun `implementation frame rejects another callable owner domain or semantic basis`() {
        val f = ImplementationShapeFixture()
        assertFalse(
            f.frame
                .copy(invocation = ImpactCallOccurrenceDocument.Declared(f.invocation.copy(callable = f.other)))
                .admits(f.basis)
        )
        assertFalse(
            f.frame
                .copy(target = f.target.copy(relation = f.relation.copy(source = f.endpoint(f.other, 'd'))))
                .admits(f.basis)
        )
        assertFalse(
            f.frame
                .copy(target = f.target.copy(ownership = f.target.ownership.copy(declaration = f.other)))
                .admits(f.basis)
        )
        val testRoot = f.target.ownership.root.copy(sourceKind = ImpactSourceKindDocument.TEST)
        assertFalse(
            f.frame.copy(target = f.target.copy(ownership = f.target.ownership.copy(root = testRoot))).admits(f.basis)
        )
        val foreign =
            ImpactSemanticBasisDocument.Published(
                f.values.text("/workspace"),
                ImpactEvidenceRevisionDocument.parse(8).value(),
            )
        val foreignFact = f.relation.copy(authority = foreign)
        val dispatch = f.dispatch.copy(facts = f.values.bounded(listOf(foreignFact, f.dispatch.facts.values[1])))
        assertFalse(f.frame.copy(target = f.target.copy(dispatch = dispatch, relation = foreignFact)).admits(f.basis))
        val wrongQuestion = f.dispatch.question.copy(subject = f.other)
        assertFalse(
            f.frame.copy(target = f.target.copy(dispatch = f.dispatch.copy(question = wrongQuestion))).admits(f.basis)
        )
    }

    @Test
    fun `call identity ignores work observations while changed coverage cannot discharge a return`() {
        val f = ImplementationShapeFixture()
        val budget = f.dispatch.question.budget.copy(maxWorkUnits = WorkUnitLimit.parse(20).value())
        val fresh =
            f.frame.copy(
                target =
                    f.target.copy(
                        dispatch =
                            f.dispatch.copy(
                                examinedWorkUnits = f.values.count(7),
                                question = f.dispatch.question.copy(budget = budget),
                            )
                    )
            )
        assertTrue(fresh.admits(f.basis))
        assertTrue(f.frame.hasSameFrameIdentity(fresh))
        assertTrue(f.returnEdge(fresh).admitsEvidence())
        val partial =
            f.frame.copy(
                target =
                    f.target.copy(
                        dispatch =
                            f.dispatch.copy(
                                coverage =
                                    ImpactImplementationCoverageDocument.TerminalIncomplete(
                                        f.values.count(2),
                                        f.values.bounded(listOf(RelationLimitationDocument.UNRESOLVED_TARGET)),
                                    )
                            )
                    )
            )
        assertTrue(partial.admits(f.basis))
        assertFalse(f.frame.hasSameFrameIdentity(partial))
        assertFalse(f.returnEdge(partial).admitsEvidence())
        val duplicate = f.argument.copy(callStack = f.values.bounded(listOf(f.frame, fresh)))
        assertFalse(duplicate.admitsExecutionOwner())
    }

    @Test
    fun `zero-fact qualified evidence survives native witness admission and refuses a substituted call`() {
        val f = ImplementationShapeFixture()
        val empty =
            f.dispatch.copy(
                facts = f.values.bounded(emptyList()),
                resultCount = f.values.count(0),
                coverage =
                    ImpactImplementationCoverageDocument.TerminalIncomplete(
                        f.values.count(0),
                        f.values.bounded(listOf(RelationLimitationDocument.UNRESOLVED_TARGET)),
                    ),
            )
        val evidence = ImpactFlowObligationEvidenceDocument.ImplementationDispatch(empty)
        val witness =
            ImpactWitnessDocument.FlowObligation(
                observationOrdinal = f.values.count(0),
                obligationOrdinal = f.values.count(0),
                site = f.argument,
                cause = ImpactFlowUnsupportedDocument.UNMODELED_CALL,
                evidence = evidence,
            )
        assertTrue(witness.hasSourceBasis(f.basis))
        assertFalse(
            witness
                .copy(site = f.argument.copy(role = ImpactValueRoleDocument.ExpressionResult))
                .hasSourceBasis(f.basis)
        )
        val wrong = evidence.copy(dispatch = empty.copy(question = empty.question.copy(subject = f.other)))
        assertFalse(witness.copy(evidence = wrong).hasSourceBasis(f.basis))
        val foreign =
            f.subject.copy(
                basis =
                    ImpactSemanticBasisDocument.Published(
                        f.values.text("/workspace"),
                        ImpactEvidenceRevisionDocument.parse(8).value(),
                    )
            )
        val wrongBasis = evidence.copy(dispatch = empty.copy(question = empty.question.copy(subject = foreign)))
        assertFalse(witness.copy(evidence = wrongBasis).hasSourceBasis(f.basis))
        assertZeroFactShape(witness)
    }

    private fun assertZeroFactShape(witness: ImpactWitnessDocument.FlowObligation) {
        val encoded = Json.encodeToJsonElement(ImpactWitnessDocument.serializer(), witness).jsonObject
        assertEquals(
            "IMPLEMENTATION_DISPATCH",
            encoded.getValue("evidence").jsonObject.getValue("type").jsonPrimitive.content,
        )
        assertEquals(
            "TERMINAL_INCOMPLETE",
            encoded
                .getValue("evidence")
                .jsonObject
                .getValue("dispatch")
                .jsonObject
                .getValue("coverage")
                .jsonObject
                .getValue("type")
                .jsonPrimitive
                .content,
        )
    }
}

internal class ImplementationShapeFixture {
    val values = ImpactFindingFixture()
    val basis = values.declaration.basis
    val subject = values.declaration.copy(file = values.text("/workspace/Api.kt"), compilerIdentity = hash('b'))
    private val implementation = subject.copy(file = values.text("/workspace/Impl.kt"), compilerIdentity = hash('c'))
    val other = subject.copy(file = values.text("/workspace/Other.kt"), compilerIdentity = hash('d'))
    val invocation = ImpactInvocationReferenceDocument(range(30, 50), subject)
    val relation =
        ImpactImplementationFactDocument(
            subject = endpoint(subject, 'b'),
            source = endpoint(implementation, 'c'),
            target = endpoint(subject, 'b'),
            occurrence = ImpactImplementationOccurrenceDocument(implementation.file, implementation.range),
            authority = basis,
            provenance = RelationProvenanceDocument.K2_AUTHORED_SOURCE,
            coverage = RelationFactCoverageDocument.EXACT_COMPILER_CONFIRMED,
        )
    private val sibling =
        relation.copy(source = endpoint(other, 'd'), occurrence = relation.occurrence.copy(file = other.file))
    val dispatch =
        ImpactImplementationDispatchDocument(
            question = values.flowDomain.copy(subject = subject, meaning = RelationKindDocument.IMPLEMENTATIONS),
            subjectFingerprint = values.text("b".repeat(64)),
            provider = RelationProviderDocument.INTELLIJ_DEFINITIONS_V2,
            facts = values.bounded(listOf(relation, sibling)),
            encodedBytes = values.count(300),
            examinedWorkUnits = values.count(2),
            resultCount = values.count(2),
            omissions = values.bounded(emptyList()),
            scopeExclusions = values.bounded(emptyList()),
            coverage = ImpactImplementationCoverageDocument.Exhausted(values.count(2)),
        )
    val target =
        ImpactCallTargetDocument.Implementation(
            dispatch,
            relation,
            ImpactSourceOwnershipDocument(
                implementation,
                ImpactSourceRootDocument(
                    module = values.text("app"),
                    buildRoot = values.text("."),
                    projectPath = values.text(":"),
                    sourceSet = values.text("main"),
                    sourceRoot = values.text("/workspace"),
                    sourceKind = ImpactSourceKindDocument.PRODUCTION,
                    provenance = ImpactSourceProvenanceDocument.AUTHORED,
                ),
            ),
        )
    val frame =
        ImpactCallFrameDocument(
            enclosing = values.declaration,
            invocation = invocation,
            callee = ImpactExecutionReferenceDocument(implementation),
            target = target,
        )
    val argument =
        ImpactValueSiteReferenceDocument(
            values.declaration,
            range(35, 40),
            ImpactValueRoleDocument.Argument(invocation, values.offset(0)),
        )

    fun endpoint(declaration: ImpactDeclarationReferenceDocument, fingerprint: Char) =
        ImpactImplementationEndpointDocument(declaration, values.text(fingerprint.toString().repeat(64)))

    fun returnEdge(returnedFrame: ImpactCallFrameDocument): ImpactCompilerTransferDocument {
        val returned =
            ImpactValueSiteReferenceDocument(
                enclosing = implementation,
                range = range(80, 90),
                role = ImpactValueRoleDocument.Return,
                callStack = values.bounded(listOf(frame)),
            )
        val result = argument.copy(range = invocation.range, role = ImpactValueRoleDocument.ExpressionResult)
        return ImpactCompilerTransferDocument(
            source = returned,
            target = result,
            kind = ImpactTransferKindDocument.SOURCE_CALL_RETURN,
            evidence = ImpactTransferEvidenceDocument.Source(ImpactSourceOperationDocument.Return(returnedFrame)),
        )
    }

    private fun hash(value: Char) = values.text("canonical-signature-sha256-v1|" + value.toString().repeat(64))

    private fun range(start: Int, end: Int) = ImpactSourceRangeDocument(values.offset(start), values.offset(end))
}

private fun <T> Refinement<T, *>.value(): T = (this as Refinement.Refined).value
