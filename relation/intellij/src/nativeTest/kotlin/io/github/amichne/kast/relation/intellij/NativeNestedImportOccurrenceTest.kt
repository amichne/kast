package io.github.amichne.kast.relation.intellij

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.JavaModuleType
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import io.github.amichne.kast.kernel.ElapsedTimeLimitMillis
import io.github.amichne.kast.kernel.EvidenceGeneration
import io.github.amichne.kast.kernel.ResourceBudget
import io.github.amichne.kast.kernel.ResultLimit
import io.github.amichne.kast.kernel.WorkUnitLimit
import io.github.amichne.kast.relation.contract.RelationBudget
import io.github.amichne.kast.relation.contract.RelationByteLimit
import io.github.amichne.kast.relation.contract.RelationCompilation
import io.github.amichne.kast.relation.contract.RelationEndpoint
import io.github.amichne.kast.relation.contract.RelationIncompleteCoverage
import io.github.amichne.kast.relation.contract.RelationMeaning
import io.github.amichne.kast.relation.contract.RelationReferenceContext
import io.github.amichne.kast.relation.contract.RelationReferenceOccurrence
import io.github.amichne.kast.relation.contract.RelationReferenceOwnership
import io.github.amichne.kast.relation.contract.RelationRequest
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryCandidate
import io.github.amichne.kast.symbol.contract.SymbolDiscoveryKind
import io.github.amichne.kast.symbol.contract.SymbolDiscoverySelection
import io.github.amichne.kast.symbol.contract.SymbolGeneratedSourcePolicy
import io.github.amichne.kast.symbol.contract.SymbolSearchScope
import io.github.amichne.kast.symbol.contract.SymbolSelector
import io.github.amichne.kast.symbol.contract.SymbolSourceKindPolicy
import io.github.amichne.kast.workspace.contract.CanonicalWorkspaceRoot
import io.github.amichne.kast.workspace.contract.ImportedWorkspaceModelState
import io.github.amichne.kast.workspace.contract.SemanticReadLease
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModel
import io.github.amichne.kast.workspace.contract.WorkspaceSearchScopeModelCompilation
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootBoundary
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootKind
import io.github.amichne.kast.workspace.contract.WorkspaceSourceRootProvenance
import io.github.amichne.kast.workspace.intellij.read.IntellijReadContributor
import io.github.amichne.kast.workspace.intellij.read.IntellijReadCounter
import io.github.amichne.kast.workspace.intellij.read.IntellijReadObservation
import kotlinx.coroutines.runBlocking
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

/** Real indexed K2 target proof through the production relation search and occurrence emitter. */
class NativeNestedImportOccurrenceTest : HeavyPlatformTestCase() {
    override fun getModuleType() = JavaModuleType.getModuleType()

    override fun runInDispatchThread() = false

    fun testNestedImportAndAliasRetainFileOwnershipAndExactRanges() {
        val consumerText =
            "package consumer\nimport proof.Outer.Nested\n" +
                "import proof.Outer.Nested as Alias\n" +
                "fun consume(value: Nested, alias: Alias) = value\n"
        lateinit var targetFile: KtFile
        ApplicationManager.getApplication().invokeAndWait {
            val source = sourceRoot()
            targetFile = sourceFile(source, "Target.kt", "package proof\nclass Outer { class Nested }\n")
            sourceFile(source, "Consumer.kt", consumerText)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        val request =
            ReadAction.compute<RelationRequest, RuntimeException> {
                request((targetFile.declarations.single() as KtClass).declarations.single() as KtClass)
            }
        val result = read(request)
        assertTrue(
            "all independently expected references must be proven: $result",
            result is RelationCompilation.Complete,
        )
        assertOccurrences(request, consumerText, (result as RelationCompilation.Complete).batch)
    }

    fun testReferenceSuccessorsDoNotRebuildTheSourceFileUniverse() {
        val consumerText =
            "package consumer\nimport proof.Outer.Nested\n" +
                "import proof.Outer.Nested as Alias\nfun consume(value: Nested, alias: Alias) = value\n"
        lateinit var targetFile: KtFile
        ApplicationManager.getApplication().invokeAndWait {
            val source = sourceRoot()
            targetFile = sourceFile(source, "Target.kt", "package proof\nclass Outer { class Nested }\n")
            sourceFile(source, "Consumer.kt", consumerText)
            IndexingTestUtil.waitUntilIndexesAreReady(project)
        }
        for ((meaning, expectedSize) in
            listOf(RelationMeaning.References to EXPECTED_REFERENCES, RelationMeaning.TypeUses to 2)) {
            for (limit in listOf(1, MEDIUM_RESULT_LIMIT, LARGE_RESULT_LIMIT)) {
                val initial =
                    ReadAction.compute<RelationRequest, RuntimeException> {
                        request(
                            (targetFile.declarations.single() as KtClass).declarations.single() as KtClass,
                            limit,
                            meaning,
                        )
                    }
                val observation = PreparationObservation()
                val occurrences = drain(initial, observation)
                assertEquals(expectedSize, occurrences.size)
                assertEquals(expectedSize, occurrences.map { it.canonicalProjection() }.toSet().size)
                assertTrue(
                    occurrences.all { it.target == initial.subject && it.authority == initial.subject.lease.identity }
                )
                val starts =
                    listOf(consumerText.indexOf("Nested,"), consumerText.indexOf("Alias)")) +
                        if (meaning == RelationMeaning.References)
                            listOf(consumerText.indexOf("Nested"), consumerText.indexOf("Nested as"))
                        else emptyList()
                assertEquals(starts.toSet(), occurrences.map { it.occurrence.range.startInclusive }.toSet())
                assertEquals(1, observation.counts[IntellijReadCounter.NATIVE_RELATION_INVENTORIES_PREPARED])
                assertEquals(
                    "retained reference locators do not require another whole-source inventory",
                    1,
                    observation.counts[IntellijReadCounter.RELATION_FILE_ENUMERATION_COMPLETE],
                )
                assertEquals(expectedSize, observation.counts[IntellijReadCounter.RELATION_K2_CONFIRMED_TARGETS])
            }
        }
    }

    private fun drain(
        initial: RelationRequest,
        observation: PreparationObservation,
    ): List<RelationReferenceOccurrence> {
        val occurrences = mutableListOf<RelationReferenceOccurrence>()
        var request = initial
        repeat(EXPECTED_REFERENCES) {
            val result = read(request, observation)
            when (result) {
                is RelationCompilation.Complete -> {
                    occurrences += result.batch.referenceOccurrences
                    return occurrences
                }
                is RelationCompilation.Qualified -> {
                    occurrences += result.batch.referenceOccurrences
                    assertTrue(
                        "bounded reference work must be retained: $result",
                        result.coverage is RelationIncompleteCoverage.Resumable,
                    )
                    val continuation = (result.coverage as RelationIncompleteCoverage.Resumable).continuation
                    request =
                        RelationRequest.resume(
                                selector = (initial.subject as RelationEndpoint.Subject).selector,
                                meaning = initial.meaning,
                                budget = initial.budget,
                                continuation = continuation,
                            )
                            .nativeRefined()
                }
                is RelationCompilation.Rejected -> fail("real compiler read rejected: ${result.reason}")
            }
        }
        error("The independently specified finite reference fixture did not exhaust")
    }

    private fun sourceRoot(): VirtualFile {
        val base = getOrCreateProjectBaseDir()
        val source =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                base.createChildDirectory(this, "src")
            }
        val sdk = JavaSdk.getInstance().createJdk("nested-import-jdk", System.getProperty("java.home"), false)
        registerTestProjectJdk(sdk)
        ModuleRootModificationUtil.updateModel(module) { model ->
            model.sdk = sdk
            model.addContentEntry(source).addSourceFolder(source, false)
        }
        return source
    }

    private fun sourceFile(source: VirtualFile, name: String, text: String): KtFile {
        val file =
            WriteCommandAction.writeCommandAction(project).compute<VirtualFile, RuntimeException> {
                source.createChildData(this, name).also { it.setBinaryContent(text.toByteArray()) }
            }
        return psiManager.findFile(file) as KtFile
    }

    private fun read(
        request: RelationRequest,
        observation: IntellijReadObservation = IntellijReadObservation.None,
    ): RelationCompilation = runBlocking {
        IntellijRelationCompilerQuery(observation = observation)
            .read(
                project = project,
                currentLease = request.subject.lease,
                request = request,
                modelCompilation = model(request.subject.lease.workspaceRoot),
            )
    }

    private fun assertOccurrences(
        request: RelationRequest,
        consumerText: String,
        batch: io.github.amichne.kast.relation.contract.RelationBatch,
    ) {
        val occurrences = batch.referenceOccurrences.sortedBy { it.occurrence.range.startInclusive }
        assertEquals(EXPECTED_REFERENCES, occurrences.size)
        assertEquals(2, batch.facts.size)
        assertEquals(listOf("consume", "consume"), batch.facts.map { it.source.name.value })
        assertTrue(batch.facts.all { it.meaning == RelationMeaning.References })
        assertTrue(batch.omissions.isEmpty())
        assertEquals(
            listOf(
                RelationReferenceContext.IMPORT,
                RelationReferenceContext.ALIASED_IMPORT,
                RelationReferenceContext.TYPE,
                RelationReferenceContext.TYPE,
            ),
            occurrences.map { it.context },
        )
        val expectedStarts =
            listOf(
                consumerText.indexOf("Nested"),
                consumerText.indexOf("Nested as"),
                consumerText.indexOf("Nested,"),
                consumerText.indexOf("Alias)"),
            )
        assertEquals(expectedStarts, occurrences.map { it.occurrence.range.startInclusive })
        assertEquals(expectedStarts.drop(2), batch.facts.map { it.occurrence.range.startInclusive })
        assertEquals(
            listOf("Nested", "Nested", "Nested", "Alias"),
            occurrences.map {
                consumerText.substring(it.occurrence.range.startInclusive, it.occurrence.range.endExclusive)
            },
        )
        assertTrue(occurrences.take(2).all { it.ownership is RelationReferenceOwnership.FileScoped })
        assertTrue(occurrences.drop(2).all { it.ownership is RelationReferenceOwnership.DeclarationOwned })
        assertTrue(occurrences.all { it.target == request.subject && it.authority == request.subject.lease.identity })
    }

    private fun request(
        target: KtClass,
        limit: Int = 20,
        meaning: RelationMeaning = RelationMeaning.References,
    ): RelationRequest {
        val file = target.containingFile.virtualFile
        val root = CanonicalWorkspaceRoot.fromCanonicalPath(file.toNioPath().parent).nativeRefined()
        val lease = SemanticReadLease(root, EvidenceGeneration.parse(1).nativeRefined())
        val sourceRoot = (model(root) as WorkspaceSearchScopeModelCompilation.Compiled).model.sourceRoots.single()
        val scope =
            SymbolSearchScope.SourceSet(
                project = sourceRoot.project,
                sourceSet = sourceRoot.sourceSet,
                sourceKinds = SymbolSourceKindPolicy.PRODUCTION_AND_TEST,
                generatedSources = SymbolGeneratedSourcePolicy.EXCLUDE,
            )
        val projection = IntellijK2RelationProjection(project, root).project(target)
        assertTrue(
            "subject identity comes from K2: $projection",
            projection is IntellijRelationDeclarationProjection.Projected,
        )
        val candidate =
            SymbolDiscoveryCandidate.fromBoundary(
                    kind = SymbolDiscoveryKind.SYMBOL,
                    rawName = "Nested",
                    lease = lease,
                    nativePath = file.toNioPath(),
                    virtualFileUrl = file.url,
                    rawOffset = target.textRange.startOffset,
                )
                .nativeRefined()
        val selection = SymbolDiscoverySelection.restore(lease, scope, candidate).nativeRefined()
        val selector =
            SymbolSelector.issue(selection, (projection as IntellijRelationDeclarationProjection.Projected).evidence)
                .nativeRefined()
        val resources =
            ResourceBudget(
                ResultLimit.parse(limit).nativeRefined(),
                WorkUnitLimit.parse(10_000).nativeRefined(),
                ElapsedTimeLimitMillis.parse(ELAPSED_MILLIS).nativeRefined(),
            )
        return RelationRequest.start(
            selector,
            meaning,
            RelationBudget(resources, RelationByteLimit.parse(BYTE_LIMIT).nativeRefined()),
        )
    }

    private fun model(workspaceRoot: CanonicalWorkspaceRoot): WorkspaceSearchScopeModelCompilation {
        val root = java.nio.file.Path.of(workspaceRoot.value)
        val model =
            WorkspaceSearchScopeModel.compile(
                workspaceRoot,
                ImportedWorkspaceModelState.COMPLETE,
                listOf(
                    WorkspaceSourceRootBoundary(
                        ideaModuleName = module.name,
                        linkedBuildRoot = root,
                        gradleProjectPath = ":main",
                        sourceSetName = "main",
                        sourceRoot = root,
                        sourceKind = WorkspaceSourceRootKind.PRODUCTION,
                        provenance = WorkspaceSourceRootProvenance.AUTHORED,
                    )
                ),
            )
        return model
    }

    private class PreparationObservation : IntellijReadObservation by IntellijReadObservation.None {
        val counts = mutableMapOf<IntellijReadCounter, Int>()

        override fun count(counter: IntellijReadCounter, contributor: IntellijReadContributor, amount: Int) {
            counts[counter] = (counts[counter] ?: 0) + amount
        }
    }

    private companion object {
        const val MEDIUM_RESULT_LIMIT = 5
        const val LARGE_RESULT_LIMIT = 20
        const val EXPECTED_REFERENCES = 4
        const val BYTE_LIMIT = 100_000L
        const val ELAPSED_MILLIS = 2_000L
    }
}
