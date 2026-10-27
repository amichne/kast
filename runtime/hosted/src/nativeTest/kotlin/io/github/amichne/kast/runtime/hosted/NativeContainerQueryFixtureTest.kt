package io.github.amichne.kast.runtime.hosted

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.OperationOutcome
import io.github.amichne.kast.protocol.contract.ImpactPathDocument
import io.github.amichne.kast.protocol.contract.ImpactPathTerminalDocument
import io.github.amichne.kast.protocol.contract.ImpactReadRejectionDocument
import io.github.amichne.kast.protocol.contract.ImpactSourceRangeDocument
import io.github.amichne.kast.protocol.contract.ImpactValueSiteReferenceDocument
import io.github.amichne.kast.protocol.contract.QueryEvidenceCursor
import io.github.amichne.kast.protocol.contract.QueryImpactProducerDocument
import io.github.amichne.kast.protocol.contract.QueryResultCursor
import io.github.amichne.kast.protocol.contract.QueryResultItemDocument
import io.github.amichne.kast.protocol.contract.QueryRunRequest
import io.github.amichne.kast.protocol.contract.QueryRunResult
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import java.nio.file.Path

/** A neutral source is authored before the production ports capture their immutable source state. */
abstract class NativeContainerQueryFixtureTest : NativeSmartModeFixtureTest() {
    abstract override fun getModuleType(): ModuleType<*>

    internal fun containerFixture(): NativePacketQueryCase {
        val files = authorSource()
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(files.base.toNioPath()).packetQueryRefined()
        val model = sourceModel(files, root)
        val services = NativePacketQueryServices(project = project, root = root, model = model, file = files.file)
        val source =
            NativePacketQuerySource(
                project = project,
                file = files.file,
                services = services,
                variant = NativePacketQueryVariant.COVERAGE,
                ownedRoot = model.sourceRoots.single(),
            )
        source.assertNoErrors()
        return NativePacketQueryCase(services, source)
    }

    private fun authorSource(): ContainerQueryFiles {
        val base = getOrCreateProjectBaseDir()
        lateinit var source: VirtualFile
        lateinit var file: VirtualFile
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                source = base.createChildDirectory(this, "src")
                file = source.createChildData(this, "ContainerFlow.kt")
                file.setBinaryContent(CONTAINER_QUERY_SOURCE.toByteArray())
            }
            configureModule(base, source)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        return ContainerQueryFiles(base, source, file)
    }

    private fun configureModule(base: VirtualFile, source: VirtualFile) {
        val sdk = JavaSdk.getInstance().createJdk("container-query-sdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { roots ->
            roots.sdk = sdk
            roots.addContentEntry(base).addSourceFolder(source, false)
            val library = roots.moduleLibraryTable.createLibrary("container-query-stdlib")
            val libraryRoots = library.modifiableModel
            val jar = Path.of(PathManager.getHomePath(), "plugins/Kotlin/kotlinc/lib/kotlin-stdlib.jar")
            libraryRoots.addRoot("jar://$jar!/", OrderRootType.CLASSES)
            libraryRoots.commit()
        }
    }

    private fun sourceModel(files: ContainerQueryFiles, root: CanonicalWorkspaceRoot): WorkspaceSearchScopeModel {
        val compiled =
            WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = files.base.toNioPath(),
                        gradleProjectPath = ":packet",
                        sourceSetName = "main",
                        sourceRoot = files.source.toNioPath(),
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        check(compiled is WorkspaceSearchScopeModelCompilation.Compiled)
        return compiled.model
    }
}

private data class ContainerQueryFiles(val base: VirtualFile, val source: VirtualFile, val file: VirtualFile)

/** Retained row and evidence windows advance independently; replay must perform no additional semantic reads. */
internal class NativeContainerQueryCapture(private val services: NativePacketQueryServices) {
    suspend fun capture(producer: QueryImpactProducerDocument): NativePacketQueryResult {
        val reader = NativePacketQueryCapture(services)
        val captured =
            reader.capture(producer = producer, targets = emptyList(), outgoing = false, declarations = emptyList())
        val complete = captured.outcome is OperationOutcome.Complete
        val diagnostics = if (complete) null else ContainerQueryDiagnostics()
        val drained = drain(reader = reader, captured = captured, complete = complete, diagnostics = diagnostics)
        check(complete) {
            "Container query was incomplete: ${services.executionSummary()}; ${diagnostics?.summary()}"
        }
        return drained
    }

    private suspend fun drain(
        reader: NativePacketQueryCapture,
        captured: NativePacketQueryResult,
        complete: Boolean,
        diagnostics: ContainerQueryDiagnostics?,
    ): NativePacketQueryResult {
        val reference = checkNotNull(captured.retained.presentationWindow).reference
        val paths = mutableListOf<ImpactPathDocument>()
        var page = captured.retained
        var rowCursor = QueryResultCursor.Start
        var evidenceCursor = QueryEvidenceCursor.Start
        val resultEnd = checkNotNull(page.presentationWindow).resultEnd
        val evidenceEnd = page.evidenceWindow?.total
        repeat(MAXIMUM_RETAINED_PAGES) {
            val window = checkNotNull(page.presentationWindow)
            check(window.reference == reference && window.start == rowCursor)
            check(window.resultEnd == resultEnd && window.itemCount == page.items.values.size)
            check(page.nextCursor == window.nextCursor)
            val previousEvidence = evidenceCursor
            evidenceCursor = admitEvidenceWindow(page, evidenceCursor, evidenceEnd)
            val emitted = pagePaths(page)
            diagnostics?.record(emitted)
            paths += emitted
            if (page.nextCursor == null && page.evidenceWindow?.nextCursor == null) {
                check(paths.size == resultEnd.value)
                return captured.copy(paths = paths.toList(), retained = page)
            }
            val nextRows = window.end
            check(nextRows != rowCursor || evidenceCursor != previousEvidence) {
                "Retained presentation made no progress"
            }
            rowCursor = nextRows
            page =
                readPage(
                    reader,
                    QueryRunRequest.ReadResult.valuePaths(
                        reference,
                        rowCursor,
                        evidenceCursor = evidenceCursor,
                    ),
                    complete,
                )
        }
        error("Container query exceeded its finite retained-page bound")
    }

    private fun pagePaths(page: QueryRunResult): List<ImpactPathDocument> =
        page.items.values.map { item ->
            check(item is QueryResultItemDocument.ValuePath)
            item.path
        }

    private fun admitEvidenceWindow(
        page: QueryRunResult,
        cursor: QueryEvidenceCursor,
        expectedEnd: QueryEvidenceCursor?,
    ): QueryEvidenceCursor {
        val window = page.evidenceWindow
        if (window == null) {
            check(expectedEnd == null)
            return cursor
        }
        check(window.start == cursor && window.total == expectedEnd)
        check(window.end.value >= cursor.value)
        return window.end
    }

    private suspend fun readPage(
        reader: NativePacketQueryCapture,
        request: QueryRunRequest.ReadResult,
        complete: Boolean,
    ): QueryRunResult {
        val reads = services.nativeFlowReads
        val outcome = reader.execute(request)
        check(services.nativeFlowReads == reads) { "Retained pagination repeated native semantic work" }
        return when (outcome) {
            is OperationOutcome.Complete -> {
                check(complete) { "Retained pagination erased the original unresolved result" }
                outcome.evidence.payload
            }
            is OperationOutcome.Qualified -> {
                check(!complete) { "Retained pagination changed query completion: $outcome" }
                outcome.evidence.payload
            }
            is OperationOutcome.Rejected -> error("Retained diagnostic read failed: ${outcome.reason}")
        }
    }

    private companion object {
        const val MAXIMUM_RETAINED_PAGES = 256
    }
}

/** Incomplete results remain failures after their bounded retained evidence has been inspected. */
private class ContainerQueryDiagnostics {
    private val frontiers = linkedSetOf<String>()
    private var pages = 0
    private var pathCount = 0

    fun record(paths: List<ImpactPathDocument>) {
        pages++
        pathCount += paths.size
        paths
            .asSequence()
            .mapNotNull { path -> frontier(path.terminal) }
            .take(MAXIMUM_FRONTIER_SUMMARIES - frontiers.size)
            .forEach(frontiers::add)
    }

    fun summary() = "retainedPages=$pages retainedPaths=$pathCount frontiers=$frontiers"

    private fun frontier(terminal: ImpactPathTerminalDocument): String? =
        when (terminal) {
            is ImpactPathTerminalDocument.SupportedDomainEnd -> null
            is ImpactPathTerminalDocument.UnresolvedFlow ->
                "UNRESOLVED_FLOW/${terminal.cause} ${site(terminal.site)} evidence=${terminal.evidence.javaClass.simpleName}"
            is ImpactPathTerminalDocument.UnresolvedRead -> rejected(terminal.rejection)
            else -> terminal.javaClass.simpleName
        }

    private fun rejected(rejection: ImpactReadRejectionDocument): String =
        when (rejection) {
            is ImpactReadRejectionDocument.Native ->
                "UNRESOLVED_READ/NATIVE/${rejection.cause} ${site(rejection.source)} work=${rejection.examinedWorkUnits.value}"
            is ImpactReadRejectionDocument.Contract ->
                "UNRESOLVED_READ/CONTRACT/${rejection.cause} ${site(rejection.source)} work=${rejection.examinedWorkUnits.value}"
        }

    private fun site(site: ImpactValueSiteReferenceDocument): String {
        val owner = site.enclosing
        val invoker = site.callStack.values.lastOrNull()?.callee?.enclosing
        return "site=${range(site.range)} role=${site.role.javaClass.simpleName} " +
            "owner=${owner.compilerIdentity.value}@${range(owner.range)} " +
            "invoker=${invoker?.compilerIdentity?.value}@${invoker?.range?.let(::range)} " +
            "stack=${site.callStack.values.size}"
    }

    private fun range(range: ImpactSourceRangeDocument) = "[${range.start.value},${range.end.value})"

    private companion object {
        const val MAXIMUM_FRONTIER_SUMMARIES = 8
    }
}

private val CONTAINER_QUERY_SOURCE =
    """
    package fixture.containerquery

    class Payload
    fun origin(): Payload = Payload()
    fun consume(value: Payload?) {}
    fun inputObserver(value: Payload) {}
    fun forbidden(value: Payload) {}

    interface Stored<out S> { val value: S }
    sealed interface Packet<out S> {
        fun <T> map(transform: (S) -> T): Packet<T>
        fun payload(): S?
    }
    data class Good<S> internal constructor(override val value: S) : Stored<S>, Packet<S> {
        override fun <T> map(transform: (S) -> T): Good<T> = transform(value).boxed()
        override fun payload(): S = value
    }
    data class Bad(val reason: Payload) : Packet<Nothing> {
        override fun <T> map(transform: (Nothing) -> T): Packet<T> = this
        override fun payload(): Nothing? = null
    }
    fun <S> S.boxed(): Good<S> = Good(this)
    fun <S, T> forward(value: Packet<S>, transform: (S) -> T): Packet<T> = value.map(transform)
    fun <S, T> forwardAgain(value: Packet<S>, transform: (S) -> T): Packet<T> = forward(value, transform)

    fun directCase() {
        val value = origin()
        consume(/*<direct-use>*/value/*</direct-use>*/)
    }
    fun concreteCase() {
        val initial = Good(origin())
        val mapped = initial.map { item -> item }
        consume(/*<concrete-use>*/mapped.value/*</concrete-use>*/)
    }
    fun implicitCase() {
        val initial = Good(origin())
        val mapped = initial.map { it }
        consume(/*<implicit-use>*/mapped.value/*</implicit-use>*/)
    }
    fun interfaceCase() {
        val initial: Packet<Payload> = Good(origin())
        val mapped = initial.map { it }
        consume(/*<interface-use>*/mapped.payload()/*</interface-use>*/)
    }
    fun forwardedCase() {
        val initial: Packet<Payload> = Good(origin())
        val mapped = forwardAgain(initial) { it }
        consume(/*<forwarded-use>*/mapped.payload()/*</forwarded-use>*/)
    }
    fun badCase() {
        val initial: Packet<Payload> = Bad(origin())
        val mapped = initial.map { forbidden(it); it }
        consume(mapped.payload())
    }
    """
        .trimIndent()
