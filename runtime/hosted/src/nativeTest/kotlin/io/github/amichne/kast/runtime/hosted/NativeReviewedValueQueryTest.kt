package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactActualExecutionDocument
import io.github.amichne.kast.protocol.contract.ImpactDeclarationReferenceDocument
import io.github.amichne.kast.protocol.contract.ImpactExecutionOwnerDocument
import io.github.amichne.kast.protocol.contract.ImpactInvocationCountKnowledgeDocument
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathStepDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedArtifactsDocument
import io.github.amichne.kast.protocol.contract.ImpactReviewedValueSemanticsDocument
import io.github.amichne.kast.protocol.contract.ImpactTransferKindDocument
import io.github.amichne.kast.protocol.contract.ImpactValuePathSegmentDocument
import io.github.amichne.kast.protocol.contract.ImpactValueRoleDocument
import io.github.amichne.kast.protocol.contract.QueryRunRejection
import kotlinx.coroutines.runBlocking

/** Frozen questions run through native K2, the query interpreter, and COMPLETE_ONLY publication. */
class NativeReviewedValueQueryTest : NativeSmartModeFixtureTest() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    fun testE0OriginalQuestionCompletesThroughElementPayload() = runBlocking {
        val fixture = prepare(NativeReviewedQueryVariant.E0)
        val result = NativeReviewedQueryCapture(fixture).capture()
        assertTrue(
            "E0 public completion failed: ${fixture.services.executionSummary()}",
            result.outcome is OperationOutcome.Complete,
        )
        val convert = fixture.source.declaration("convert")
        val field = fixture.source.payload()
        val path =
            result.paths.single { path ->
                (path.terminal as? ImpactPathTerminalDocument.SupportedDomainEnd)?.observation?.source?.enclosing ==
                    convert
            }
        assertPrefix(path)
        assertElementPayload(path, convert, field)
        val sourceReturn =
            path.steps.values
                .filterIsInstance<ImpactPathStepDocument.Compiler>()
                .single { step ->
                    step.transfer.kind == ImpactTransferKindDocument.SOURCE_CALL_RETURN &&
                        step.transfer.target.range == reviewedQueryRange(CALLBACK_CALL_START, CALLBACK_CALL_END)
                }
                .transfer
        val owner = sourceReturn.target.executionOwner
        assertTrue(owner is ImpactExecutionOwnerDocument.ReviewedCallback)
        owner as ImpactExecutionOwnerDocument.ReviewedCallback
        assertEquals(reviewedQueryRange(CALLBACK_BODY_START, CALLBACK_BODY_END), owner.binding.body.range)
        assertEquals(convert, sourceReturn.target.enclosing)
        assertEquals(listOf(ImpactValuePathSegmentDocument.Field(field)), sourceReturn.target.valuePath.values)
        assertEquals(E0_COMPLETE_ROUTE_SIZE, path.steps.values.size)
        assertReviewedRule(path)
    }

    fun testE1CompleteUpstreamRouteKeepsAllFiveCompilerTransfers() = runBlocking {
        val fixture = prepare(NativeReviewedQueryVariant.E1)
        val result = NativeReviewedQueryCapture(fixture).capture()
        assertTrue(result.outcome is OperationOutcome.Complete)
        val path = result.paths.single()
        assertPrefix(path)
        assertEquals(UPSTREAM_ROUTE_SIZE, path.steps.values.size)
        val end = path.terminal as ImpactPathTerminalDocument.SupportedDomainEnd
        assertEquals(fixture.source.declaration("toClientEnvelope"), end.observation.source.enclosing)
        assertEquals(
            listOf(ImpactValuePathSegmentDocument.Field(fixture.source.payload())),
            end.observation.source.valuePath.values,
        )
    }

    fun testE4UnrelatedProducerCannotEnterTheConvertOutput() = runBlocking {
        val fixture = prepare(NativeReviewedQueryVariant.E4)
        val result = NativeReviewedQueryCapture(fixture).capture()
        assertTrue(result.outcome is OperationOutcome.Complete)
        val convert = fixture.source.declaration("convert")
        val path = result.paths.single()
        assertPrefix(path)
        assertEquals(UPSTREAM_ROUTE_SIZE, path.steps.values.size)
        assertFalse(path.steps.values.any { it is ImpactPathStepDocument.ReviewedLibrary })
        assertFalse(
            path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().any {
                it.transfer.target.enclosing == convert
            }
        )
    }

    fun testE5NameOnlyMapRejectsCompletionWithoutReviewedOutput() = runBlocking {
        val fixture = prepare(NativeReviewedQueryVariant.E5)
        val result = NativeReviewedQueryCapture(fixture).capture()
        assertTrue(result.outcome is OperationOutcome.Rejected)
        val rejection = result.outcome as OperationOutcome.Rejected
        assertTrue(rejection.reason is QueryRunRejection.CompletionUnproven)
        assertTrue(result.paths.all { path -> path.steps.values.none { it is ImpactPathStepDocument.ReviewedLibrary } })
        val compilerTargets =
            result.paths.flatMap { path ->
                path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().map { it.transfer.target }
            }
        assertTrue(compilerTargets.none { ImpactValuePathSegmentDocument.Element in it.valuePath.values })
    }

    fun testE6EmptyInputKeepsConditionalMeaningWithoutActualExecutionOrCount() = runBlocking {
        val fixture = prepare(NativeReviewedQueryVariant.E6)
        val result = NativeReviewedQueryCapture(fixture).capture()
        assertTrue(result.outcome is OperationOutcome.Complete)
        val convert = fixture.source.declaration("convert")
        val path =
            result.paths.single { path ->
                (path.terminal as? ImpactPathTerminalDocument.SupportedDomainEnd)?.observation?.source?.enclosing ==
                    convert
            }
        assertElementPayload(path, convert, fixture.source.payload())
        assertReviewedRule(path)
    }

    private fun assertPrefix(path: ImpactPathDocument) {
        assertEquals(reviewedQueryRange(REVIEWED_QUERY_SEED_START, REVIEWED_QUERY_SEED_END), path.producer.range)
        assertEquals(
            listOf(
                ImpactTransferKindDocument.RETURN,
                ImpactTransferKindDocument.SOURCE_CALL_RETURN,
                ImpactTransferKindDocument.ARGUMENT,
                ImpactTransferKindDocument.CONSTRUCTOR_FIELD,
                ImpactTransferKindDocument.RETURN,
            ),
            path.steps.values.take(UPSTREAM_ROUTE_SIZE).map { (it as ImpactPathStepDocument.Compiler).transfer.kind },
        )
    }

    private fun assertElementPayload(
        path: ImpactPathDocument,
        enclosing: ImpactDeclarationReferenceDocument,
        field: ImpactDeclarationReferenceDocument,
    ) {
        val terminal = path.terminal as ImpactPathTerminalDocument.SupportedDomainEnd
        assertEquals(enclosing, terminal.observation.source.enclosing)
        assertEquals(ImpactValueRoleDocument.Return, terminal.observation.source.role)
        assertEquals(
            listOf(ImpactValuePathSegmentDocument.Element, ImpactValuePathSegmentDocument.Field(field)),
            terminal.observation.source.valuePath.values,
        )
    }

    private fun assertReviewedRule(path: ImpactPathDocument) {
        val transfer = path.steps.values.filterIsInstance<ImpactPathStepDocument.ReviewedLibrary>().single().transfer
        val relation =
            transfer.binding.relation
                as io.github.amichne.kast.protocol.contract.ImpactReviewedValueRelationDocument.CallbackCorrespondence
        val binding =
            transfer.binding.authority as io.github.amichne.kast.protocol.contract.ImpactReviewedValueBindingDocument
        assertEquals(
            ImpactReviewedValueSemanticsDocument.CONDITIONAL_CALLBACK_RESULT_TO_OUTPUT_ELEMENT,
            relation.semantics,
        )
        assertEquals(ImpactActualExecutionDocument.NOT_ESTABLISHED, binding.actualExecution)
        assertEquals(ImpactInvocationCountKnowledgeDocument.NOT_ESTABLISHED, binding.invocationCount)
        assertEquals("kotlin.collections.map", binding.selected.qualifiedIdentity.value)
        assertEquals("kotlin/collections/Iterable<T>", binding.selected.receiver.value)
        assertEquals(listOf("(T)->R"), binding.selected.valueParameters.values.map { it.value })
        assertEquals(0, binding.selected.formalIndex.value)
        assertReviewedArtifacts(binding.artifacts)
        assertEquals(transfer.source.enclosing.basis, binding.basis)
        assertEquals(transfer.source.enclosing, binding.enclosing)
        assertEquals(transfer.source.valuePath.values, transfer.target.valuePath.values.drop(1))
        assertTrue(
            path.steps.values.filterIsInstance<ImpactPathStepDocument.Compiler>().none {
                it.transfer.source == transfer.source && it.transfer.target == transfer.target
            }
        )
    }

    private fun assertReviewedArtifacts(document: ImpactReviewedArtifactsDocument) {
        assertTrue(document is io.github.amichne.kast.protocol.contract.ImpactReviewedCollectionArtifactsDocument)
        val artifacts = document as io.github.amichne.kast.protocol.contract.ImpactReviewedCollectionArtifactsDocument
        assertEquals(NativeReviewedQueryFixture.BINARY_SHA256, artifacts.binaryArtifactSha256.value)
        assertEquals(NativeReviewedQueryFixture.SOURCES_SHA256, artifacts.sourceArtifactSha256.value)
        assertEquals(
            "aad6a5398189369be27f65952a5f2c935e3bf00d166e2046a0fb4be0d007a85c",
            artifacts.sourceEntrySha256.value,
        )
        assertEquals("7f74e68459e9c8ae6c69510014b6fe0f565b9389ddbe5acb5e181dd773558878", artifacts.facadeSha256.value)
        assertEquals(
            "e5afca255b864c70152adeeecd88367f5477966ab26bd16f8db2b5c56c995296",
            artifacts.implementationSha256.value,
        )
        val sections =
            artifacts.sections as io.github.amichne.kast.protocol.contract.ImpactReviewedCollectionSectionsDocument.Map
        assertEquals(REVIEWED_MAP_FIRST_LINE, sections.mapLines.first.value)
        assertEquals(REVIEWED_MAP_LAST_LINE, sections.mapLines.last.value)
        assertEquals(REVIEWED_MAP_TO_FIRST_LINE, sections.mapToLines.first.value)
        assertEquals(REVIEWED_MAP_TO_LAST_LINE, sections.mapToLines.last.value)
    }

    private fun prepare(variant: NativeReviewedQueryVariant): NativeReviewedQueryCase {
        lateinit var sdk: Sdk
        ApplicationManager.getApplication().invokeAndWait {
            sdk = JavaSdk.getInstance().createJdk("reviewed-query-sdk", System.getProperty("java.home"), false)
            registerTestProjectJdk(sdk)
        }
        return NativeReviewedQueryFixture(
                project = project,
                base = getOrCreateProjectBaseDir(),
                module = module,
                sdk = sdk,
            )
            .prepare(variant)
    }
}

private const val CALLBACK_CALL_START = 436
private const val CALLBACK_CALL_END = 457
private const val CALLBACK_BODY_START = 434
private const val CALLBACK_BODY_END = 459
private const val E0_COMPLETE_ROUTE_SIZE = 9
private const val UPSTREAM_ROUTE_SIZE = 5
private const val REVIEWED_MAP_FIRST_LINE = 1744
private const val REVIEWED_MAP_LAST_LINE = 1746
private const val REVIEWED_MAP_TO_FIRST_LINE = 1819
private const val REVIEWED_MAP_TO_LAST_LINE = 1823
