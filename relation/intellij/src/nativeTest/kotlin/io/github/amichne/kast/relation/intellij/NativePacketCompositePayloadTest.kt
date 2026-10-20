package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.CallbackBindingEvidence
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueCallbackTarget
import io.github.amichne.kast.relation.contract.ValueExecutionBody
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFormalOrigin
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValuePredicate
import io.github.amichne.kast.relation.contract.ValuePredicates
import io.github.amichne.kast.relation.contract.ValueProducerOrigin
import io.github.amichne.kast.relation.contract.ValueProducerSeedRead
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueSourceOperation
import io.github.amichne.kast.relation.contract.ValueSourceTransfer
import io.github.amichne.kast.relation.contract.ValueTransfer
import io.github.amichne.kast.relation.contract.ValueTransferEvidence
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CompilerGroundedSymbolEvidence
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLambdaExpression
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Original S2 isolates payload projection and callback entry before later carrier reconstruction. */
class NativePacketCompositePayloadTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-composite-payload-jdk"

    fun testOriginalMapFormalProjectsDistinctSuccessAndErrorFieldsWithScopedGuards() {
        val fixture = packetFixture(COMPOSITE)
        val expected = declarations(fixture)
        val entry = mapEntry(fixture)
        val projected = payloads(fixture, entry, expected)
        assertFalse(projected.first.target.predicates == projected.second.target.predicates)
        assertEquals(entry.formal.callStack, projected.first.target.callStack)
        assertEquals(entry.formal.callStack, projected.second.target.callStack)
        println("COMPOSITE_RESULT row=C2.1 stage=scoped-payload-fields remaining=C2.2-C2.10 status=NOT_RUN")
    }

    fun testOriginalSuccessPayloadEntersTheSuppliedImplicitItThroughBuiltinInvoke() {
        val fixture = packetFixture(COMPOSITE)
        val expected = declarations(fixture)
        val entry = mapEntry(fixture)
        val success = payloads(fixture, entry, expected).first.target
        val argument = next(fixture, success, ValueTransferKind.ARGUMENT).target
        assertTrue("C2.1 needs a real builtin invoke argument", argument.role is ValueRole.FunctionArgument)
        val call = (argument.role as ValueRole.FunctionArgument).call
        assertEquals(expected.invokeRange, call.range)
        assertEquals("kotlin.Function1.invoke", call.signature.qualifiedIdentity.value)
        assertEquals(entry.formal.executionOwner, call.executionOwner)
        val entered = next(fixture, argument, ValueTransferKind.SOURCE_CALL_ENTRY).target
        assertEquals(expected.callbackRange, entered.executionOwner.range)
        assertTrue(
            "C2.1 must preserve the genuine supplied body",
            entered.executionOwner.body is ValueExecutionBody.Anonymous,
        )
        val formal = ((entered.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
        assertEquals(ValueFormalOrigin.Implicit, formal.origin)
        assertEquals("it", formal.name.value)
        assertEquals(0, formal.position.value)
        assertEquals(expected.callbackRange, formal.range)
        assertEquals(argument.predicates, entered.predicates)
        val frame = entered.callStack.frames.last()
        assertEquals(argument.callStack.frames + frame, entered.callStack.frames)
        val target = frame.target as ValueCallbackTarget
        assertEquals(call, frame.invocation)
        assertEquals(entry.formal.callStack.frames.last(), target.supplyingFrame)
        val binding = target.flow.binding as CallbackBindingEvidence.Bound
        assertEquals((entry.receiver.role as ValueRole.Receiver).call, binding.binding.invocation)
        assertEquals(expected.invokeRange, target.selected.occurrence.range)
        println("COMPOSITE_RESULT row=C2.1 stage=implicit-callback-formal remaining=C2.2-C2.10 status=NOT_RUN")
    }

    private fun payloads(
        fixture: NativePacketValueFixture,
        entry: CompositeMapEntry,
        expected: CompositePayloadDeclarations,
    ): Pair<ValueTransfer, ValueTransfer> {
        val result = step(fixture, entry.formal)
        val fields = result.transfers.filter { it.kind == ValueTransferKind.FIELD_READ }
        assertEquals(
            "C2.1 needs distinct Success.value and Error.value projections. " +
                "Frontier=${result.obligations.map { it.cause }}. Callback payload and C2.2-C2.10 remain NOT_REACHED",
            setOf(expected.success.anchor, expected.error.anchor),
            fields.map { it.target.range }.toSet(),
        )
        val success = fields.single { it.target.range == expected.success.anchor }
        val error = fields.single { it.target.range == expected.error.anchor }
        assertPayload(success, expected.success, entry)
        assertPayload(error, expected.error, entry)
        return success to error
    }

    private fun assertPayload(transfer: ValueTransfer, expected: CompositePayloadProjection, entry: CompositeMapEntry) {
        assertEquals(entry.formal, transfer.source)
        assertTrue(
            "C2.1 field projection needs explicit scoped guard evidence",
            transfer.evidence is ValueTransferEvidence.Guarded,
        )
        val evidence = transfer.evidence as ValueTransferEvidence.Guarded
        val field = (evidence.delegate as ValueSourceTransfer).operation as ValueSourceOperation.ReadField
        assertEquals(expected.field.compilerIdentity, field.field.declaration.compilerIdentity)
        assertEquals(expected.field.range, field.field.declaration.range)
        val predicates = transfer.target.predicates.entries.filterIsInstance<ValuePredicate.Subtype>()
        assertEquals(1, predicates.size)
        val predicate = predicates.single()
        assertEquals(expected.type.compilerIdentity, predicate.resolvedType.compilerIdentity)
        assertEquals(expected.type.range, predicate.resolvedType.range)
        assertEquals(
            entry.receiver.withState(predicates = ValuePredicates.Empty).nativeRefined(),
            predicate.subject.occurrence,
        )
        assertEquals(entry.formal.callStack, transfer.target.callStack)
        assertEquals(entry.formal.executionOwner, transfer.target.executionOwner)
    }

    private fun mapEntry(fixture: NativePacketValueFixture): CompositeMapEntry {
        val owner = PacketDeclaration(CLIENT, "OffersClientImpl", "readOffers")
        val request = fixture.selection(owner, "execute", PacketDeclaration(WIRE, "Call", "execute"))
        val seeded = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .seed(project = project, current = fixture.lease, request = request, model = fixture.model)
        }
        assertTrue("Original S2 seed failed before payload assertions: $seeded", seeded is ValueProducerSeedRead.Seeded)
        val seed = (seeded as ValueProducerSeedRead.Seeded).seed
        assertEquals(request.anchor, seed.invocation.range)
        assertEquals(
            request.expectedCallable.compilerIdentity,
            (seed.origin as ValueProducerOrigin.Declared).invocation.callable.compilerIdentity,
        )
        val receiver = next(fixture, seed.site, ValueTransferKind.RECEIVER).target
        val formal = next(fixture, receiver, ValueTransferKind.SOURCE_CALL_ENTRY).target
        val map = fixture.callableDeclaration(PacketDeclaration(OUTCOME, "Outcome", "map"))
        assertEquals(map.compilerIdentity, formal.enclosing.compilerIdentity)
        assertEquals(map.range, formal.range)
        assertEquals((receiver.role as ValueRole.Receiver).call, formal.callStack.frames.last().invocation)
        return CompositeMapEntry(receiver, formal)
    }

    private fun next(fixture: NativePacketValueFixture, source: ValueSite, kind: ValueTransferKind): ValueTransfer {
        val result = step(fixture, source)
        assertEquals(
            "C2.1 needs $kind at ${source.range}. Frontier=${result.obligations.map { it.cause }}. " +
                "Later callback and carrier obligations remain NOT_REACHED",
            listOf(kind),
            result.transfers.map { it.kind },
        )
        return result.transfers.single()
    }

    private fun step(fixture: NativePacketValueFixture, source: ValueSite): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, RelationSearchBoundary.RETAINED_SUBJECT),
                    model = fixture.model,
                )
        }
        assertTrue("C2.1 native runner failed before its transfer assertion: $result", result is ValueFlowRead.Observed)
        val read = (result as ValueFlowRead.Observed).step
        println(
            "COMPOSITE_PAYLOAD_STAGE source=${source.range} role=${source.role::class.simpleName} " +
                "transfers=${read.transfers.map { it.kind to it.target.range }} frontier=${read.obligations.map { it.cause }}"
        )
        return read
    }

    private fun declarations(fixture: NativePacketValueFixture): CompositePayloadDeclarations =
        ReadAction.compute<CompositePayloadDeclarations, RuntimeException> {
            val outcome = psi(OUTCOME)
            val map =
                PsiTreeUtil.collectElementsOfType(outcome, KtNamedFunction::class.java).single { it.name == "map" }
            val transform =
                PsiTreeUtil.collectElementsOfType(map, KtCallExpression::class.java).single {
                    it.calleeExpression?.text == "transform"
                }
            val callback =
                PsiTreeUtil.collectElementsOfType(psi(CLIENT), KtLambdaExpression::class.java)
                    .single {
                        it.bodyExpression?.statements?.singleOrNull()?.text == "it.body.toClientResponse()"
                    }
                    .functionLiteral
            assertTrue("Original callback must use implicit it", callback.valueParameters.isEmpty())
            CompositePayloadDeclarations(
                success =
                    projection(
                        fixture = fixture,
                        file = outcome,
                        type = "Success",
                        anchor = range(checkNotNull(transform.valueArguments.single().getArgumentExpression())),
                    ),
                error =
                    projection(
                        fixture = fixture,
                        file = outcome,
                        type = "Error",
                        anchor =
                            fixture
                                .argumentExpression(PacketDeclaration(OUTCOME, "Outcome", "map"), "error", "value")
                                .anchor,
                    ),
                invokeRange = range(transform),
                callbackRange = range(callback),
            )
        }

    private fun projection(
        fixture: NativePacketValueFixture,
        file: KtFile,
        type: String,
        anchor: ExactDeclarationTextRange,
    ): CompositePayloadProjection {
        val declaration = PsiTreeUtil.collectElementsOfType(file, KtClass::class.java).single { it.name == type }
        val property = declaration.primaryConstructorParameters.single { it.name == "value" }
        val field = evidence(fixture, property)
        assertEquals(CompilerSymbolKind.PROPERTY, field.kind)
        val klass = evidence(fixture, declaration)
        assertEquals(CompilerSymbolKind.CLASSLIKE, klass.kind)
        return CompositePayloadProjection(field, klass, anchor)
    }

    private fun evidence(
        fixture: NativePacketValueFixture,
        declaration: PsiNamedElement,
    ): CompilerGroundedSymbolEvidence {
        val projected = IntellijK2RelationProjection(project, fixture.lease.workspaceRoot).project(declaration)
        assertTrue(
            "Independent source declaration failed: $projected",
            projected is IntellijRelationDeclarationProjection.Projected,
        )
        return (projected as IntellijRelationDeclarationProjection.Projected).evidence
    }

    private fun psi(path: String): KtFile {
        val relative = "src/" + path.removePrefix("src/main/kotlin/")
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(relative))
        return PsiManager.getInstance(project).findFile(file) as KtFile
    }

    private companion object {
        const val CLIENT = "src/main/kotlin/fixture/client/OffersClient.kt"
        const val FEATURE = "src/main/kotlin/fixture/feature/OffersProcessor.kt"
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val WIRE = "src/main/kotlin/fixture/wire/OffersApi.kt"
        val COMPOSITE = listOf(OUTCOME, WIRE, CLIENT, FEATURE)
    }
}

private data class CompositeMapEntry(val receiver: ValueSite, val formal: ValueSite)

private data class CompositePayloadProjection(
    val field: CompilerGroundedSymbolEvidence,
    val type: CompilerGroundedSymbolEvidence,
    val anchor: ExactDeclarationTextRange,
)

private data class CompositePayloadDeclarations(
    val success: CompositePayloadProjection,
    val error: CompositePayloadProjection,
    val invokeRange: ExactDeclarationTextRange,
    val callbackRange: ExactDeclarationTextRange,
)
