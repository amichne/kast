@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.RelationSearchBoundary
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFlowUnsupportedCause
import io.github.amichne.kast.relation.contract.ValueFormalOrigin
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.symbol.contract.CanonicalCompilerSignature
import io.github.amichne.kast.symbol.contract.CompilerSymbolIdentity
import io.github.amichne.kast.symbol.contract.ExactDeclarationTextRange
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** Compiler-valid library entry preserves the formal. Compiled PSI cannot prove source-body exhaustion. */
class NativePacketLibraryBodyReadTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-library-body-read-jdk"

    fun testCompiledFirstOrNullReceiverFormalKeepsAnExternalBodyObligation() {
        val fixture = packetFixture(NativePacketCompositeSuccessSource.PATHS)
        val expected = NativePacketFirstOrNullFrontierSource(project, getOrCreateProjectBaseDir(), fixture)
        val source = ReadAction.compute<ValueSite, RuntimeException> { expected.original() }
        val formal = entered(fixture, source, expected.boundary)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), formal.role)
        assertCompiledBody(fixture = fixture, site = formal, boundary = expected.boundary, compiled = true)
        assertExternalBody(read(fixture, formal, expected.boundary))
    }

    fun testGenuineSourceSameNameUnusedReceiverFormalStillExhaustsSupported() {
        val fixture = packetFixture(listOf(NativePacketFirstOrNullFrontierSource.CONTROL))
        val expected = NativePacketFirstOrNullFrontierSource(project, getOrCreateProjectBaseDir(), fixture)
        val source = ReadAction.compute<ValueSite, RuntimeException> { expected.sourceDefined() }
        val formal = entered(fixture, source, expected.boundary)
        assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), formal.role)
        assertCompiledBody(fixture = fixture, site = formal, boundary = expected.boundary, compiled = false)
        val result = read(fixture, formal, expected.boundary)
        assertTrue(result.transfers.isEmpty())
        assertTrue(result.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, result.terminal)
    }

    fun testCompiledListOfValueParameterFormalKeepsAnExternalBodyObligation() {
        val fixture = packetFixture(ECHO_PATHS, SymbolSourceKindPolicy.PRODUCTION_AND_TEST)
        val expected = listOfParameter()
        val input =
            fixture.argumentExpression(
                PacketDeclaration(ECHO, "EchoOffersClient", "readOffers"),
                "listOf",
                "request.destination",
            )
        val expression = resolve(fixture, input)
        val actuals = read(fixture, expression, libraryBoundary).transfers
        assertEquals(listOf(ValueTransferKind.ARGUMENT), actuals.map { it.kind })
        val argument = actuals.single().target
        val role = argument.role as ValueRole.Argument
        assertEquals(expected.identity, role.call.callable.compilerIdentity)
        assertEquals(0, role.position.value)
        val formal = entered(fixture, argument, libraryBoundary)
        val parameter = ((formal.role as ValueRole.Formal).position as ValueFormalPosition.Parameter).parameter
        assertEquals(expected.identity, parameter.callable.compilerIdentity)
        assertEquals(expected.parameter, parameter.range)
        assertEquals(ValueFormalOrigin.Declared(expected.parameter), parameter.origin)
        assertEquals(expected.name, parameter.name.value)
        assertEquals(0, parameter.position.value)
        assertCompiledBody(fixture = fixture, site = formal, boundary = libraryBoundary, compiled = true)
        assertExternalBody(read(fixture, formal, libraryBoundary))
    }

    private fun entered(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        boundary: RelationSearchBoundary,
    ): ValueSite {
        val result = read(fixture, source, boundary)
        val entries = result.transfers.filter { transfer -> transfer.kind == ValueTransferKind.SOURCE_CALL_ENTRY }
        assertEquals("The valid compiler binding must preserve its exact formal entry", 1, entries.size)
        val formal = entries.single().target
        assertEquals(source.valuePath, formal.valuePath)
        assertEquals(source.predicates, formal.predicates)
        assertEquals(source.callStack.frames.size + 1, formal.callStack.frames.size)
        return formal
    }

    private fun assertExternalBody(step: ValueFlowStep) {
        assertTrue("A compiled stub must not supply a source-body edge", step.transfers.isEmpty())
        assertEquals(
            "A compiled stub must not prove supported source-body exhaustion",
            listOf(ValueFlowUnsupportedCause.EXTERNAL_CALL),
            step.obligations.map { it.cause },
        )
        assertTrue(step.obligations.all { obligation -> obligation.site == step.source })
        assertEquals(ValueFlowTerminal.Unresolved, step.terminal)
    }

    private fun assertCompiledBody(
        fixture: NativePacketValueFixture,
        site: ValueSite,
        boundary: RelationSearchBoundary,
        compiled: Boolean,
    ) =
        ReadAction.run<RuntimeException> {
            val domain =
                RelationRequest.start(
                    subject = site.enclosing,
                    meaning = RelationMeaning.References,
                    budget = fixture.budget,
                    boundary = boundary,
                )
            val prepared = IntellijValueFlowCompilerAdapter().prepare(project, domain, fixture.model)
            assertTrue(prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready)
            val ready = prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
            val found = ready.projection.subject(ready.scope, site.enclosing)
            assertTrue("The formal needs its real compiler declaration", found is IntellijRelationSubjectLookup.Found)
            val function = (found as IntellijRelationSubjectLookup.Found).declaration as KtNamedFunction
            assertEquals(compiled, (function.containingFile as KtFile).isCompiled)
            assertNotNull("Both compiled stubs and the source control expose PSI bodies", function.bodyExpression)
            println(
                "LIBRARY_BODY_READ_AUTHORITY compiled=$compiled function=${range(function)} " +
                    "body=${function.bodyExpression?.let(::range)} file=${site.enclosing.file.stableValue}"
            )
        }

    private fun listOfParameter(): LibraryBodyParameter =
        ReadAction.compute<LibraryBodyParameter, RuntimeException> {
            val file =
                checkNotNull(
                    getOrCreateProjectBaseDir().findFileByRelativePath("test/fixture/client/EchoOffersClient.kt")
                )
            val psi = PsiManager.getInstance(project).findFile(file) as KtFile
            val call =
                PsiTreeUtil.collectElementsOfType(psi, KtCallExpression::class.java).single { candidate ->
                    candidate.calleeExpression?.text == "listOf"
                }
            IntellijReadObservation.None.observedAnalyze(call) {
                val resolved = checkNotNull(call.resolveCall())
                val symbol = resolved.signature.symbol
                assertEquals(KaSymbolOrigin.LIBRARY, symbol.origin)
                val projected = symbol.compilerProjection(this)
                assertTrue(projected is IntellijCompilerProjectionResult.Projected)
                val declaration = (projected as IntellijCompilerProjectionResult.Projected).projection
                val signature = declaration.signature as CanonicalCompilerSignature.Function
                assertEquals("kotlin.collections.listOf", signature.qualifiedIdentity.value)
                assertEquals(listOf("T"), signature.valueParameters.map { it.value })
                assertEquals(1, signature.typeParameterCount.value)
                val argument = call.valueArguments.single().getArgumentExpression()
                assertNotNull(argument)
                val parameter = resolved.valueArgumentMapping[checkNotNull(argument)]?.symbol
                assertNotNull("The actual source argument needs its real compiler parameter", parameter)
                val actual = checkNotNull(parameter)
                assertEquals(symbol.valueParameters.single(), actual)
                assertFalse(actual.isVararg)
                val declarationPsi = symbol.psi as KtNamedFunction
                assertTrue((declarationPsi.containingFile as KtFile).isCompiled)
                LibraryBodyParameter(declaration.identity, range(checkNotNull(actual.psi)), actual.name.asString())
            }
        }

    private fun resolve(fixture: NativePacketValueFixture, expression: PacketExpression): ValueSite {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .resolve(
                    project = project,
                    current = fixture.lease,
                    enclosing = expression.enclosing,
                    anchor = expression.anchor,
                    domain =
                        RelationRequest.start(
                            subject = RelationEndpoint.subject(expression.enclosing),
                            meaning = RelationMeaning.References,
                            budget = fixture.budget,
                            boundary = libraryBoundary,
                        ),
                    model = fixture.model,
                )
        }
        assertTrue(
            "The Echo source argument must resolve before library-body assertions",
            result is ValueSiteResolution.Found,
        )
        return (result as ValueSiteResolution.Found).site
    }

    private fun read(
        fixture: NativePacketValueFixture,
        source: ValueSite,
        boundary: RelationSearchBoundary,
    ): ValueFlowStep {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, boundary),
                    model = fixture.model,
                )
        }
        assertTrue("The real library-body position needs a native read", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step.also { step ->
            println(
                "LIBRARY_BODY_READ_FRONTIER source=${source.range} " +
                    "transfers=${step.transfers.map { transfer -> transfer.kind to transfer.target.range }} " +
                    "causes=${step.obligations.map { obligation -> obligation.cause }} " +
                    "terminal=${step.terminal} work=${step.examinedWorkUnits.value}"
            )
        }
    }

    private val libraryBoundary =
        RelationSearchBoundary.Explicit(
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.INCLUDE,
            )
        )

    private companion object {
        const val ECHO = "src/test/kotlin/fixture/client/EchoOffersClient.kt"
        val ECHO_PATHS =
            listOf(
                NativePacketCompositeSuccessSource.OUTCOME,
                NativePacketCompositeSuccessSource.WIRE,
                NativePacketCompositeSuccessSource.CLIENT,
                ECHO,
            )
    }
}

private data class LibraryBodyParameter(
    val identity: CompilerSymbolIdentity,
    val parameter: ExactDeclarationTextRange,
    val name: String,
)
