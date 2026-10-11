package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.vfs.VirtualFile
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryContainment
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectory
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDirectoryConstraint
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCall
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallOutcome
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCallScope
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Real compiled scope API calls; no native-index or distinct-file claim. */
class RelationScopeApiObservationTest {
    private val request = RelationReadTest().request(RelationMeaning.Callers)
    private val root = request.subject.lease.workspaceRoot
    private val model =
        (WorkspaceSearchScopeModel.compile(
                root,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        "app.main",
                        Path.of(root.value),
                        ":app",
                        "main",
                        Path.of(root.value).resolve("app/src/main/kotlin"),
                        WorkspaceSourceRootKind.PRODUCTION,
                        WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            ) as WorkspaceSearchScopeModelCompilation.Compiled)
            .model
    private val bothKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST
    private val authored = SymbolGeneratedSourcePolicy.EXCLUDE

    private fun workspace() = SymbolSearchScope.Workspace(bothKinds, authored, SymbolLibraryPolicy.EXCLUDE)

    @Test
    fun `scope API decisions count actual calls independently of delivered provider sites`() {
        val observed = ScopeObservation()
        val sdk = RelationScopeSdkFixture(libraryContains = { true })
        val compiled = compiled(sdk, workspace().copy(libraries = SymbolLibraryPolicy.INCLUDE), observed)
        val scope = compiled.nativeScope
        val file = UnavailablePathFile()
        repeat(2) { assertTrue(scope.contains(file)) }
        assertTrue(compiled.admitProviderSite(file) == RelationProviderScopeAdmission.ADMITTED)
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.main")))
        assertFalse(scope.isSearchInModuleContent(sdk.module("unknown")))
        assertTrue(scope.isSearchInModuleContent(sdk.module("app.main"), false))
        assertFalse(scope.isSearchInModuleContent(sdk.module("app.main"), true))
        assertTrue(scope.isSearchInLibraries)
        assertEquals(3, observed.counts[IntellijReadCounter.RELATION_SCOPE_FILES_ADMITTED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_PROVIDER_SCOPE_ADMITTED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_SCOPE_MODULES_ADMITTED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_SCOPE_MODULES_EXCLUDED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_SCOPE_MODULE_SOURCE_KINDS_ADMITTED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_SCOPE_MODULE_SOURCE_KINDS_EXCLUDED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_SCOPE_LIBRARY_SEARCH_ADMITTED])
        assertEquals(
            listOf(
                    IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_MODULE_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_MODULE_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_MODULE_SOURCE_KIND_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_MODULE_SOURCE_KIND_MEMBERSHIP,
                    IntellijReadCall.RELATION_SCOPE_LIBRARY_POLICY,
                )
                .map { it to IntellijReadCallOutcome.RETURNED },
            observed.completed,
        )
        sdk.assertConsumed()
    }

    @Test
    fun `scope exclusion and unavailable paths return finite negative decisions`() {
        val observed = ScopeObservation()
        val sdk = RelationScopeSdkFixture()
        val scope = compiled(sdk, workspace(), observed).nativeScope
        assertFalse(scope.contains(UnavailablePathFile()))
        assertFalse(scope.isSearchInLibraries)
        assertEquals(
            mapOf(
                IntellijReadCounter.RELATION_SCOPE_FILES_EXCLUDED to 1,
                IntellijReadCounter.RELATION_SCOPE_LIBRARY_SEARCH_EXCLUDED to 1,
            ),
            observed.counts,
        )
        assertEquals(2, observed.completed.size)
        assertTrue(observed.completed.all { it.second == IntellijReadCallOutcome.RETURNED })
        sdk.assertConsumed()
    }

    @Test
    fun `scope base rejection precedes path effects and counts an excluded decision`() {
        val observed = ScopeObservation()
        val sdk = RelationScopeSdkFixture(allContains = { false })
        val scope = compiled(sdk, workspace(), observed).nativeScope
        assertFalse(scope.contains(UnavailablePathFile(allowPath = false)))
        assertEquals(mapOf(IntellijReadCounter.RELATION_SCOPE_FILES_EXCLUDED to 1), observed.counts)
        sdk.assertConsumed()
    }

    @Test
    fun `scope exceptional exits retain the throwable and no fabricated membership decision`() {
        for ((failure, expected) in
            listOf(
                CancellationException("Owned scope cancellation") to IntellijReadCallOutcome.CANCELLED,
                IllegalStateException("Owned scope failure") to IntellijReadCallOutcome.FAILED,
            )) {
            val observed = ScopeObservation()
            val sdk = RelationScopeSdkFixture(allContains = { throw failure })
            val scope = compiled(sdk, workspace(), observed).nativeScope
            assertSame(failure, assertThrows<RuntimeException> { scope.contains(UnavailablePathFile(false)) })
            assertEquals(emptyMap<IntellijReadCounter, Int>(), observed.counts)
            assertEquals(listOf(IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP to expected), observed.completed)
            sdk.assertConsumed()
        }
    }

    @Test
    fun `live source membership is a child of file checks and retains actual success and failure`() {
        val file = AbsolutePathFile(Path.of(root.value).resolve("app/src/main/kotlin/A.kt"))
        val observed = ScopeObservation()
        val sdk =
            RelationScopeSdkFixture(
                sourceContains = {
                    assertSame(file, it)
                    true
                }
            )
        assertTrue(compiled(sdk, workspace(), observed).nativeScope.contains(file))
        assertEquals(listOf("SOURCE", "EXCLUDED"), sdk.fileIndexCalls)
        assertEquals(
            listOf(
                IntellijReadCall.RELATION_SCOPE_SOURCE_MEMBERSHIP to IntellijReadCallOutcome.RETURNED,
                IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP to IntellijReadCallOutcome.RETURNED,
            ),
            observed.completed,
        )
        assertEquals(
            mapOf(
                IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES to 2,
                IntellijReadCounter.RELATION_SCOPE_FILES_ADMITTED to 1,
            ),
            observed.counts,
        )
        sdk.assertConsumed()
        val failure = CancellationException("Owned file-index cancellation")
        val failed = ScopeObservation()
        val rejectedSdk = RelationScopeSdkFixture(sourceContains = { throw failure })
        val scope = compiled(rejectedSdk, workspace(), failed).nativeScope
        assertSame(failure, assertThrows<CancellationException> { scope.contains(file) })
        assertEquals(listOf("SOURCE"), rejectedSdk.fileIndexCalls)
        assertEquals(
            listOf(
                IntellijReadCall.RELATION_SCOPE_SOURCE_MEMBERSHIP to IntellijReadCallOutcome.CANCELLED,
                IntellijReadCall.RELATION_SCOPE_FILE_MEMBERSHIP to IntellijReadCallOutcome.CANCELLED,
            ),
            failed.completed,
        )
        assertEquals(mapOf(IntellijReadCounter.RELATION_PATH_OWNERSHIP_PROBES to 2), failed.counts)
        rejectedSdk.assertConsumed()
    }

    @Test
    fun `unrelated directory files do not enter live source membership`() {
        val observed = ScopeObservation()
        val sdk = RelationScopeSdkFixture(sourceContains = { true })
        val admittedPaths = mutableListOf<Path>()
        val constraints =
            SymbolDiscoveryConstraints.None.copy(
                directory =
                    SymbolDiscoveryDirectoryConstraint(
                        (SymbolDiscoveryDirectory.parse("app/src/main/kotlin/selected") as Refinement.Refined).value,
                        SymbolDiscoveryContainment.DESCENDANTS,
                    )
            )
        val scope =
            compiled(sdk, workspace(), observed, constraints) { path ->
                    admittedPaths.add(path)
                    true
                }
                .nativeScope
        repeat(256) { index ->
            assertFalse(
                scope.contains(AbsolutePathFile(Path.of(root.value).resolve("app/src/main/kotlin/other/$index.kt")))
            )
        }
        assertTrue(scope.contains(AbsolutePathFile(Path.of(root.value).resolve("app/src/main/kotlin/selected/A.kt"))))
        assertEquals(listOf(Path.of(root.value).resolve("app/src/main/kotlin/selected/A.kt")), admittedPaths)
        assertEquals(listOf("SOURCE", "EXCLUDED"), sdk.fileIndexCalls)
        assertEquals(256, observed.counts[IntellijReadCounter.RELATION_SCOPE_FILES_EXCLUDED])
        assertEquals(1, observed.counts[IntellijReadCounter.RELATION_SCOPE_FILES_ADMITTED])
        assertEquals(1, observed.completed.count { it.first == IntellijReadCall.RELATION_SCOPE_SOURCE_MEMBERSHIP })
        sdk.assertConsumed()
    }

    @Test
    fun `unowned paths do not enter live source membership and eligible native rejection remains authoritative`() {
        val observed = ScopeObservation()
        val sdk = RelationScopeSdkFixture(sourceContains = { false })
        val scope = compiled(sdk, workspace(), observed).nativeScope
        repeat(256) { index ->
            assertFalse(scope.contains(AbsolutePathFile(Path.of(root.value).resolve("app/build/$index.kt"))))
        }
        assertFalse(scope.contains(AbsolutePathFile(Path.of(root.value).resolve("app/src/main/kotlin/A.kt"))))
        assertEquals(listOf("SOURCE"), sdk.fileIndexCalls)
        assertEquals(257, observed.counts[IntellijReadCounter.RELATION_SCOPE_FILES_EXCLUDED])
        assertFalse(IntellijReadCounter.RELATION_SCOPE_FILES_ADMITTED in observed.counts)
        assertEquals(1, observed.completed.count { it.first == IntellijReadCall.RELATION_SCOPE_SOURCE_MEMBERSHIP })
        sdk.assertConsumed()
    }

    private class AbsolutePathFile(private val path: Path) : UnavailablePathFile() {
        override fun toNioPath(): Path = path
    }

    private fun compiled(
        sdk: RelationScopeSdkFixture,
        selected: SymbolSearchScope,
        observation: IntellijReadObservation,
        constraints: SymbolDiscoveryConstraints = SymbolDiscoveryConstraints.None,
        fileAdmission: (Path) -> Boolean = { true },
    ): CompiledRelationScope =
        (IntellijRelationScopeCompiler(fileAdmission)
                .compile(
                    sdk.project,
                    request,
                    WorkspaceSearchScopeModelCompilation.Compiled(model),
                    selected,
                    constraints,
                    observation = observation,
                ) as IntellijRelationScopeCompilation.Compiled)
            .scope

    private class ScopeObservation : IntellijReadObservation {
        val counts = mutableMapOf<IntellijReadCounter, Int>()
        val completed = mutableListOf<Pair<IntellijReadCall, IntellijReadCallOutcome>>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            assertEquals(IntellijReadContributor.NONE, contributor)
            counts[counter] = (counts[counter] ?: 0) + amount
        }

        override fun terminated(
            reason: io.github.amichne.kast.workspace.intellij.read.IntellijReadTermination,
            contributor: IntellijReadContributor,
        ) = error("Unexpected scope termination: $reason")

        override fun enterCall(call: IntellijReadCall): IntellijReadCallScope =
            object : IntellijReadCallScope {
                private var finished = false

                override fun finish(outcome: IntellijReadCallOutcome) {
                    check(!finished)
                    finished = true
                    completed += call to outcome
                }
            }
    }

    private open class UnavailablePathFile(private val allowPath: Boolean = true) : VirtualFile() {
        override fun toNioPath(): Path {
            check(allowPath) { "Unexpected path effect after base scope rejection" }
            throw UnsupportedOperationException("Owned non-NIO file")
        }

        override fun getName(): String = error("Unexpected file name")

        override fun getFileSystem(): com.intellij.openapi.vfs.VirtualFileSystem = error("Unexpected filesystem")

        override fun getPath(): String = error("Unexpected path")

        override fun isWritable(): Boolean = error("Unexpected writable check")

        override fun isDirectory(): Boolean = error("Unexpected directory check")

        override fun isValid(): Boolean = error("Unexpected validity check")

        override fun getParent(): VirtualFile = error("Unexpected parent")

        override fun getChildren(): Array<VirtualFile> = error("Unexpected children")

        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
            error("Unexpected output stream")

        override fun contentsToByteArray(): ByteArray = error("Unexpected content")

        override fun getTimeStamp(): Long = error("Unexpected timestamp")

        override fun getLength(): Long = error("Unexpected length")

        override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) =
            error("Unexpected refresh")

        override fun getInputStream(): InputStream = error("Unexpected input stream")
    }
}
