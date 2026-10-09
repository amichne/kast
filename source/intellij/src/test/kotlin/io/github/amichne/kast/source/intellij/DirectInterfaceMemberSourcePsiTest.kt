package io.github.amichne.kast.source.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiManager
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.source.contract.Containment
import io.github.amichne.kast.source.contract.DeclarationKind
import io.github.amichne.kast.source.contract.DeclarationKindSelection
import io.github.amichne.kast.source.contract.DeclarationSemanticIdentity
import io.github.amichne.kast.source.contract.DeclarationVisibility
import io.github.amichne.kast.source.contract.EntityFilter
import io.github.amichne.kast.source.contract.EntitySelection
import io.github.amichne.kast.source.contract.SourceEntity
import io.github.amichne.kast.source.contract.SourceEntityLimit
import io.github.amichne.kast.source.contract.SourceEntityName
import io.github.amichne.kast.source.contract.SourceRange
import io.github.amichne.kast.source.contract.SourceReadContext
import io.github.amichne.kast.source.contract.SourceReadScope
import io.github.amichne.kast.source.contract.SourceRegionKind
import io.github.amichne.kast.source.contract.SourceSelector
import io.github.amichne.kast.source.contract.SourceSnapshot
import io.github.amichne.kast.source.contract.SourceTextIdentity
import io.github.amichne.kast.source.contract.Utf16CodeUnitCount
import io.github.amichne.kast.source.contract.Utf16CodeUnitOffset
import io.github.amichne.kast.source.contract.VisibilitySelection
import io.github.amichne.kast.symbol.contract.CanonicalWorkspaceFilePath
import io.github.amichne.kast.symbol.contract.CompilerSymbolKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidateLocation
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryConstraints
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryDeclarationKinds
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryFileIdentity
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolLibraryPolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceStateIdentity
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.kotlin.cli.extensionsStorage
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Physical PSI and production source enumeration. Visibility is injected; K2 anchor validation remains separate. */
class DirectInterfaceMemberSourcePsiTest {
    @Test
    fun `anonymous object source lookup label comes from actual object literal PSI`(@TempDir home: Path) {
        physical(home, "fun owner() = object : Runnable { override fun run() = Unit }\nobject Named") { file, _ ->
            val objects =
                com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(
                        file,
                        org.jetbrains.kotlin.psi.KtObjectDeclaration::class.java,
                    )
                    .toList()
            assertEquals(listOf("<anonymous-object>", "Named"), objects.map { it.compilerDeclarationName() })
            assertTrue(objects.first().isObjectLiteral())
        }
    }

    @Test
    fun `direct interface inventory proves exact structural parents and retains member kind constraints`(
        @TempDir home: Path
    ) {
        val text =
            """
            interface CacheManager {
                fun <T : Any> execute(load: () -> T, useCached: (T) -> Boolean): T
                val enabled: Boolean
                class Nested { fun hidden() = 1 }
            }
            fun unrelated() = 1
            """
                .trimIndent()
        physical(home, text) { file, snapshot ->
            val owner = file.declarations.first() as KtClass
            assertTrue(owner.isInterface())
            assertTrue(file.isPhysical)
            val range =
                SourceRange.create(
                        snapshot,
                        Utf16CodeUnitOffset.parse(owner.textRange.startOffset).refined(),
                        Utf16CodeUnitOffset.parse(owner.textRange.endOffset).refined(),
                    )
                    .refined()
            val anchor = SourceSelector.issueRoot(range, SourceRegionKind.DECLARATION)
            val projectedNames = mutableListOf<String>()
            val projection = inventory(file, snapshot, owner, anchor, projectedNames)
            val completed =
                assertInstanceOf(
                    IntellijSourceEntityPage.Complete::class.java,
                    assertInstanceOf(NativeSourceEntityProjection.Projected::class.java, projection).page,
                )
            assertTrue(completed.limitations.isEmpty())
            val members = completed.entities.map { assertInstanceOf(SourceEntity.Declaration::class.java, it) }
            assertEquals(listOf("execute", "enabled"), projectedNames)
            assertEquals(
                listOf("execute", "enabled"),
                members.map { (it.selector.name as SourceEntityName.Present).value },
            )
            assertEquals(
                listOf(text.indexOf("fun <T"), text.indexOf("val enabled")),
                members.map { it.selector.range.startInclusive.value },
            )
            assertMemberAuthority(members, anchor, snapshot)
        }
    }

    private fun directMembers(): EntitySelection.Matching =
        EntitySelection.matching(
                Containment.DIRECT,
                listOf(
                    EntityFilter.Declarations(
                        DeclarationKindSelection.from(setOf(DeclarationKind.FUNCTION, DeclarationKind.PROPERTY))
                            .refined(),
                        VisibilitySelection.Any,
                    )
                ),
            )
            .refined()

    private fun inventory(
        file: KtFile,
        snapshot: SourceSnapshot,
        owner: KtClass,
        anchor: SourceSelector,
        projectedNames: MutableList<String>,
    ): NativeSourceEntityProjection {
        val selection = directMembers()
        return IntellijSourceEntityAttempt.collect(
            IntellijSourceExecution(
                ResourceBudget(
                    ResultLimit.parse(10).refined(),
                    WorkUnitLimit.parse(1_000).refined(),
                    ElapsedTimeLimitMillis.parse(1_000).refined(),
                ),
                { 0L },
            ),
            selection,
            IntellijSourceEntityCursor(0),
            SourceEntityLimit.parse(10).refined(),
        ) { attempt ->
            NativeSourceEntityEnumerator(
                    LiveSourceDocument(snapshot, file.text, file),
                    NativeSourceRegion(owner.textRange, SourceRegionKind.DECLARATION, owner),
                    anchor,
                    includeDeclarations = true,
                    includeParameters = false,
                    includeCalls = false,
                    includeReferences = false,
                    containment = Containment.DIRECT,
                    attempt = attempt,
                    visibility = { declaration, _ ->
                        projectedNames += checkNotNull(declaration.name)
                        DeclarationVisibility.PUBLIC
                    },
                    target = { error("Declaration supply does not resolve call targets") },
                )
                .enumerate()
        }
    }

    private fun assertMemberAuthority(
        members: List<SourceEntity.Declaration>,
        anchor: SourceSelector,
        snapshot: SourceSnapshot,
    ) {
        val rootScope = snapshot.readScope as SourceReadScope.Constrained
        assertEquals(setOf(CompilerSymbolKind.CLASSLIKE), rootScope.constraints.declarationKinds!!.values)
        for (member in members) {
            assertEquals(0, member.nestingDepth.value)
            assertEquals(anchor.fingerprint, member.parentSelector.fingerprint)
            assertSame(snapshot, member.selector.snapshot)
            val candidate = (member.semanticIdentity as DeclarationSemanticIdentity.Candidate).selector.selection
            val location = candidate.candidate.location as SymbolDiscoveryCandidateLocation.Declaration
            assertEquals(snapshot.lease, candidate.lease)
            assertEquals(snapshot.file, location.file)
            assertEquals(member.selector.range.startInclusive.value, location.offset.value)
            assertEquals((snapshot.readScope as SourceReadScope.Constrained).scope, candidate.scope)
            val expected =
                if (member.kind == DeclarationKind.FUNCTION) CompilerSymbolKind.FUNCTION
                else CompilerSymbolKind.PROPERTY
            assertEquals(setOf(expected), candidate.constraints.declarationKinds!!.values)
        }
    }

    @OptIn(
        CompilerConfiguration.Internals::class,
        org.jetbrains.kotlin.K1Deprecation::class,
        org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi::class,
    )
    private fun physical(home: Path, text: String, assertion: (KtFile, SourceSnapshot) -> Unit) {
        val properties =
            listOf("idea.home.path", "idea.config.path", "idea.system.path").associateWith(System::getProperty)
        Files.createDirectories(home.resolve("bin"))
        Files.writeString(home.resolve("bin/idea.properties"), "")
        System.setProperty("idea.home.path", home.toString())
        System.setProperty("idea.config.path", home.resolve("config").toString())
        System.setProperty("idea.system.path", home.resolve("system").toString())
        val disposable = Disposer.newDisposable()
        try {
            val environment =
                KotlinCoreEnvironment.createForTests(
                    disposable,
                    CompilerConfiguration().apply { extensionsStorage = CompilerPluginRegistrar.ExtensionStorage() },
                    EnvironmentConfigFiles.JVM_CONFIG_FILES,
                )
            val path = home.resolve("Cache.kt")
            Files.writeString(path, text)
            val virtual = checkNotNull(environment.findLocalFile(path.toRealPath().toString()))
            ApplicationManager.getApplication().runReadAction {
                val file = PsiManager.getInstance(environment.project).findFile(virtual) as KtFile
                val snapshot = snapshot(home, path, text)
                assertion(file, snapshot)
            }
        } finally {
            val application = ApplicationManager.getApplication()
            if (application == null) Disposer.dispose(disposable)
            else application.runWriteAction { Disposer.dispose(disposable) }
            properties.forEach { (key, value) ->
                if (value == null) System.clearProperty(key) else System.setProperty(key, value)
            }
        }
    }

    private fun snapshot(home: Path, path: Path, text: String): SourceSnapshot {
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(home.toRealPath()).refined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(42).refined())
        val scope =
            SymbolSearchScope.Workspace(
                SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                SymbolGeneratedSourcePolicy.EXCLUDE,
                SymbolLibraryPolicy.EXCLUDE,
            )
        val constraints =
            SymbolDiscoveryConstraints.None.copy(
                declarationKinds = SymbolDiscoveryDeclarationKinds.from(setOf(CompilerSymbolKind.CLASSLIKE)).refined()
            )
        return SourceSnapshot.create(
            SourceReadContext.Published(
                lease,
                WorkspaceStateIdentity.parse("workspace-state-v1|physical").refined(),
            ),
            SymbolDiscoveryFileIdentity.Workspace(
                CanonicalWorkspaceFilePath.fromCanonicalPath(root, path.toRealPath()).refined()
            ),
            SourceTextIdentity.fromNormalizedCommittedText(text),
            Utf16CodeUnitCount.parse(text.length).refined(),
            SourceReadScope.Constrained(scope, constraints),
        )
    }
}

private fun <Value, Failure> Refinement<Value, Failure>.refined(): Value =
    when (this) {
        is Refinement.Refined -> value
        is Refinement.Rejected -> error("Invalid fixture: $failure")
    }
