@file:OptIn(org.jetbrains.kotlin.analysis.api.KaExperimentalApi::class)

package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.relation.contract.ValueFlowRead
import io.github.amichne.kast.relation.contract.ValueFlowRequest
import io.github.amichne.kast.relation.contract.ValueFlowStep
import io.github.amichne.kast.relation.contract.ValueFlowTerminal
import io.github.amichne.kast.relation.contract.ValueFormalPosition
import io.github.amichne.kast.relation.contract.ValueReceiverKind
import io.github.amichne.kast.relation.contract.ValueRole
import io.github.amichne.kast.relation.contract.ValueSite
import io.github.amichne.kast.relation.contract.ValueTransferKind
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import io.github.amichne.kast.workspace.intellij.read.observedAnalyze
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibraryModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaSourceModule
import org.jetbrains.kotlin.analysis.api.symbols.KaSymbolOrigin
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction

/** This probe captures library body authority and its next frontier. Binding entry alone is not a source-body claim. */
class NativePacketLibraryBodyProbeTest : NativePacketValueFixtureTest() {
    override val fixtureSdkName = "packet-library-body-probe-jdk"

    fun testOriginalFirstOrNullLibraryPsiAndNextFormalFrontier() = capture(Subject.LIBRARY)

    fun testSourceSameNameKeepsGenuineEntryAndUnusedFormalExhaustion() = capture(Subject.SOURCE)

    private fun capture(subject: Subject) {
        val paths =
            when (subject) {
                Subject.LIBRARY -> NativePacketCompositeSuccessSource.PATHS
                Subject.SOURCE -> listOf(NativePacketFirstOrNullFrontierSource.CONTROL)
            }
        val fixture = packetFixture(paths)
        val expected = NativePacketFirstOrNullFrontierSource(project, getOrCreateProjectBaseDir(), fixture)
        val source =
            ReadAction.compute<ValueSite, RuntimeException> {
                val receiver =
                    when (subject) {
                        Subject.LIBRARY -> expected.original()
                        Subject.SOURCE -> expected.sourceDefined()
                    }
                declaration(fixture = fixture, expected = expected, source = receiver, subject = subject)
                receiver
                    .withState(
                        valuePath =
                            NativePacketFirstOrNullModelRows.inputPath(
                                NativePacketFirstOrNullModelRows.Row.ORIGINAL_ELEMENT_ZERO
                            )
                    )
                    .nativeRefined()
            }
        entries(fixture = fixture, expected = expected, source = source, subject = subject)
    }

    private fun entries(
        fixture: NativePacketValueFixture,
        expected: NativePacketFirstOrNullFrontierSource,
        source: ValueSite,
        subject: Subject,
    ) {
        val step = observed(read(fixture, expected, source))
        val entries = step.transfers.filter { it.kind == ValueTransferKind.SOURCE_CALL_ENTRY }
        assertTrue("One receiver call needs at most one matching receiver formal", entries.size <= 1)
        if (subject == Subject.SOURCE) assertEquals("The genuine source control must enter its body", 1, entries.size)
        for (entry in entries) {
            assertEquals(ValueRole.Formal(ValueFormalPosition.Receiver(ValueReceiverKind.EXTENSION)), entry.target.role)
            assertEquals(source.valuePath, entry.target.valuePath)
            val next = read(fixture, expected, entry.target)
            if (subject == Subject.SOURCE) assertSourceExhaustion(observed(next))
        }
        println("LIBRARY_BODY_RESULT subject=$subject entries=${entries.size} capture=COMPLETE models=NOT_RUN")
    }

    private fun declaration(
        fixture: NativePacketValueFixture,
        expected: NativePacketFirstOrNullFrontierSource,
        source: ValueSite,
        subject: Subject,
    ) {
        val expression = originalCall(subject)
        val selected = (source.role as ValueRole.Receiver).call.callable
        IntellijReadObservation.None.observedAnalyze(expression) {
            val resolved = checkNotNull(expression.resolveCall())
            val symbol = resolved.signature.symbol
            val projected = symbol.compilerProjection(this)
            assertTrue(projected is IntellijCompilerProjectionResult.Projected)
            assertEquals(
                selected.compilerIdentity,
                (projected as IntellijCompilerProjectionResult.Projected).projection.identity,
            )
            assertEquals(
                if (subject == Subject.LIBRARY) KaSymbolOrigin.LIBRARY else KaSymbolOrigin.SOURCE,
                symbol.origin,
            )
            val module = symbol.containingModule
            val kind =
                when (module) {
                    is KaSourceModule -> ModuleKind.SOURCE
                    is KaLibraryModule -> ModuleKind.LIBRARY
                    is KaLibrarySourceModule -> ModuleKind.LIBRARY_SOURCE
                    else -> ModuleKind.OTHER
                }
            println(
                "LIBRARY_BODY_SYMBOL subject=$subject origin=${symbol.origin} module=$kind " +
                    "moduleSubtype=${module.javaClass.simpleName.take(MAX_NAME)} " +
                    "endpointFile=${selected.file.stableValue}"
            )
            recordPsi("K2_DECLARATION", symbol.psi)
        }
        providerDeclaration(fixture, expected, source)
    }

    private fun providerDeclaration(
        fixture: NativePacketValueFixture,
        expected: NativePacketFirstOrNullFrontierSource,
        source: ValueSite,
    ) {
        val domain =
            RelationRequest.start(
                subject = source.enclosing,
                meaning = RelationMeaning.References,
                budget = fixture.budget,
                boundary = expected.boundary,
            )
        val prepared = IntellijValueFlowCompilerAdapter().prepare(project, domain, fixture.model)
        assertTrue(prepared is IntellijValueFlowCompilerAdapter.Prepared.Ready)
        val ready = prepared as IntellijValueFlowCompilerAdapter.Prepared.Ready
        when (val selected = ready.projection.subject(ready.scope, (source.role as ValueRole.Receiver).call.callable)) {
            is IntellijRelationSubjectLookup.Found -> recordPsi("PROVIDER_SUBJECT", selected.declaration)
            is IntellijRelationSubjectLookup.Rejected ->
                println("LIBRARY_BODY_PROVIDER status=REJECTED reason=${selected.reason}")
        }
    }

    private fun recordPsi(label: String, element: PsiElement?) {
        val file = element?.containingFile
        val function = element as? KtNamedFunction
        val body = function?.bodyExpression
        println(
            "LIBRARY_BODY_PSI label=$label type=${element?.javaClass?.simpleName?.take(MAX_NAME)} " +
                "range=${element?.let(::range)} fileType=${file?.javaClass?.simpleName?.take(MAX_NAME)} " +
                "compiled=${(file as? KtFile)?.isCompiled} physical=${file?.isPhysical} url=${file?.virtualFile?.url}"
        )
        println(
            "LIBRARY_BODY_EXPRESSION label=$label present=${body != null} " +
                "kind=${body?.javaClass?.simpleName?.take(MAX_NAME)} range=${body?.let(::range)}"
        )
        val navigation = element?.navigationElement
        println(
            "LIBRARY_BODY_NAVIGATION label=$label sameElement=${navigation === element} " +
                "type=${navigation?.javaClass?.simpleName?.take(MAX_NAME)} range=${navigation?.let(::range)} " +
                "url=${navigation?.containingFile?.virtualFile?.url}"
        )
    }

    private fun originalCall(subject: Subject): KtCallExpression {
        val path =
            when (subject) {
                Subject.LIBRARY -> "src/fixture/feature/OffersProcessor.kt"
                Subject.SOURCE -> "src/fixture/controls/FirstElementModelControls.kt"
            }
        val text =
            when (subject) {
                Subject.LIBRARY -> "response.links.firstOrNull()"
                Subject.SOURCE -> "values.firstOrNull()"
            }
        val file = checkNotNull(getOrCreateProjectBaseDir().findFileByRelativePath(path))
        val psi = PsiManager.getInstance(project).findFile(file) as KtFile
        val qualified =
            PsiTreeUtil.collectElementsOfType(psi, KtDotQualifiedExpression::class.java).single { it.text == text }
        return qualified.selectorExpression as KtCallExpression
    }

    private fun read(
        fixture: NativePacketValueFixture,
        expected: NativePacketFirstOrNullFrontierSource,
        source: ValueSite,
    ): ValueFlowRead {
        val result = runBlocking {
            IntellijValueFlowCompilerAdapter()
                .read(
                    project = project,
                    current = fixture.lease,
                    request = ValueFlowRequest(source, fixture.budget, expected.boundary),
                    model = fixture.model,
                )
        }
        when (result) {
            is ValueFlowRead.Observed ->
                println(
                    "LIBRARY_BODY_FRONTIER variant=OBSERVED source=${source.range} " +
                        "file=${source.enclosing.file.stableValue} " +
                        "transfers=${result.step.transfers.map { it.kind to it.target.range }} " +
                        "causes=${result.step.obligations.map { it.cause }} " +
                        "terminal=${result.step.terminal} work=${result.step.examinedWorkUnits.value}"
                )
            is ValueFlowRead.Rejected -> println("LIBRARY_BODY_FRONTIER variant=REJECTED cause=${result.cause}")
            is ValueFlowRead.ContractRejected ->
                println("LIBRARY_BODY_FRONTIER variant=CONTRACT_REJECTED cause=${result.cause}")
            is ValueFlowRead.Suspended -> println("LIBRARY_BODY_FRONTIER variant=SUSPENDED cause=${result.cause}")
        }
        return result
    }

    private fun observed(result: ValueFlowRead): ValueFlowStep {
        assertTrue("The source receiver prerequisite must produce a native read", result is ValueFlowRead.Observed)
        return (result as ValueFlowRead.Observed).step
    }

    private fun assertSourceExhaustion(step: ValueFlowStep) {
        assertTrue(step.transfers.isEmpty())
        assertTrue(step.obligations.isEmpty())
        assertEquals(ValueFlowTerminal.SupportedDomainExhausted, step.terminal)
    }

    private enum class Subject {
        LIBRARY,
        SOURCE,
    }

    private enum class ModuleKind {
        SOURCE,
        LIBRARY,
        LIBRARY_SOURCE,
        OTHER,
    }

    private companion object {
        const val MAX_NAME = 256
    }
}
