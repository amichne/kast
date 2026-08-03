package io.github.amichne.kast.idea

import io.github.amichne.kast.idea.backend.KastIndexerBackend

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.DependencyScope
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixture
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import io.github.amichne.kast.api.contract.FilePosition
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.SearchScope
import io.github.amichne.kast.api.contract.SearchScopeKind
import io.github.amichne.kast.api.contract.ServerLimits
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.RuntimeState
import io.github.amichne.kast.api.contract.TypeHierarchyDirection
import io.github.amichne.kast.api.contract.query.ImplementationsQuery
import io.github.amichne.kast.api.contract.query.ReferencesQuery
import io.github.amichne.kast.api.contract.query.RenameQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.SymbolQuery
import io.github.amichne.kast.api.contract.query.TypeHierarchyQuery
import io.github.amichne.kast.api.contract.query.WorkspaceFilesQuery
import io.github.amichne.kast.api.contract.query.WorkspaceSearchQuery
import io.github.amichne.kast.api.contract.result.ResultCardinality
import io.github.amichne.kast.api.contract.result.ReferenceOccurrence
import io.github.amichne.kast.api.contract.result.CallRelationsResult
import io.github.amichne.kast.api.contract.result.HierarchyRelationsResult
import io.github.amichne.kast.api.contract.result.ImplementationRelationsResult
import io.github.amichne.kast.api.contract.result.RelationshipResultEvidence
import io.github.amichne.kast.api.contract.result.RelationshipSearchCoverage
import io.github.amichne.kast.api.contract.result.RelationshipSearchLimitation
import io.github.amichne.kast.api.contract.result.ExactRenameOccurrence
import io.github.amichne.kast.api.contract.result.ExactRenameProof
import io.github.amichne.kast.api.contract.result.MutationPostconditionStatus
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.RenameOccurrenceProvenance
import io.github.amichne.kast.api.contract.skill.KastCallersQuery
import io.github.amichne.kast.api.contract.skill.KastExactSymbolSelector
import io.github.amichne.kast.api.contract.skill.KastHierarchyQuery
import io.github.amichne.kast.api.contract.skill.KastImplementationsQuery
import io.github.amichne.kast.api.contract.skill.WrapperCallDirection
import io.github.amichne.kast.api.protocol.ConflictException
import io.github.amichne.kast.api.protocol.AnalysisException
import io.github.amichne.kast.api.protocol.MutationProofIncompleteException
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.indexstore.api.index.FileContentHash
import io.github.amichne.kast.indexstore.api.index.FileIndexStage
import io.github.amichne.kast.indexstore.api.index.FileStageLimitation
import io.github.amichne.kast.indexstore.api.index.FileStageVersions
import io.github.amichne.kast.indexstore.api.reference.SymbolReferenceRow
import io.github.amichne.kast.indexstore.api.reference.SymbolReferencePage
import io.github.amichne.kast.indexstore.api.reference.SourceIndexGeneration
import io.github.amichne.kast.indexstore.api.stage.RelationshipFileStageUpdate
import io.github.amichne.kast.indexstore.store.SqliteSourceIndexStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.jetbrains.jps.model.java.JavaModuleSourceRootTypes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

@TestApplication
internal class KastIndexerBackendContractTestCoverage : KastIndexerBackendContractTestFixture() {
    private val exactImageRenameSource =
        "\uFEFFpackage demo.exactimage\r\n\r\n" +
            "fun rawName(value: String): String = \"😀 ${'$'}value\"\r\n" +
            "fun useRawName(): String = rawName(\"Kast\")\r\n"
    private val exactImageRenameFileFixture: TestFixture<PsiFile> = mainSourceRootFixture.psiFileFixture(
        "ExactImageRename.kt",
        exactImageRenameSource,
    )

    @Test
    fun `rename postcondition verifier accepts exact adjusted occurrence set and rejects occurrence drift`() =
        runBlocking {
            ensureProjectReady()
            val targetFile = sampleFile
            val usageFile = sampleUsageFileFixture.get()
            val targetPath = readAction { targetFile.virtualFile.path }
            val usagePath = readAction { usageFile.virtualFile.path }
            val targetText = readAction { targetFile.text }
            val usageText = readAction { usageFile.text }
            val targetOffset = targetText.indexOf("greet")
            val workspaceRoot = commonWorkspaceRoot(targetPath, usagePath)
            val backend = backend(workspaceRoot)
            val targetBytes = Files.readAllBytes(Path.of(targetPath))
            val usageBytes = Files.readAllBytes(Path.of(usagePath))
            val currentReference = backend.findReferences(
                ReferencesQuery(
                    position = FilePosition(targetPath, targetOffset),
                    includeDeclaration = false,
                ).parsed(),
            ).references.single()
            val usageOffset = currentReference.location.startOffset
            val oldName = "oldName"
            val targetPreimage = targetText.replaceRange(targetOffset, targetOffset + 5, oldName).toByteArray()
            val usagePreimage = usageText.replaceRange(usageOffset, usageOffset + 5, oldName).toByteArray()
            val oldTarget = SymbolIdentity(
                fqName = "demo.$oldName",
                kind = SymbolKind.FUNCTION,
                declarationFile = io.github.amichne.kast.api.contract.NormalizedPath.parse(targetPath),
                declarationStartOffset = NonNegativeInt(targetOffset),
            )
            val oldOccurrence = ExactRenameOccurrence(
                reference = currentReference.copy(
                    location = currentReference.location.copy(
                        endOffset = currentReference.location.endOffset + oldName.length - 5,
                        preview = currentReference.location.preview.replace("greet", oldName),
                    ),
                ),
                resolvedTarget = oldTarget,
                provenance = RenameOccurrenceProvenance.COMPILER,
            )
            val completeCoverage = RelationshipSearchCoverage.complete()
            val proof = ExactRenameProof.of(
                target = oldTarget,
                requiredGeneration = MutationSemanticGeneration(1),
                evidence = RelationshipResultEvidence.Complete(
                    ResultCardinality.Exact(1),
                    completeCoverage,
                ),
                occurrences = listOf(oldOccurrence),
            )
            val edits = listOf(
                TextEdit(targetPath, targetOffset, targetOffset + oldName.length, "greet"),
                TextEdit(usagePath, usageOffset, usageOffset + oldName.length, "greet"),
            )
            val images = listOf(
                ExactFileImage.of(targetPath, targetPreimage, targetBytes),
                ExactFileImage.of(usagePath, usagePreimage, usageBytes),
            )
            val bytesBefore = images.associate { image ->
                image.filePath.value to Files.readAllBytes(Path.of(image.filePath.value))
            }

            val verified = backend.verifyMutationPostcondition(
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.Rename(proof, edits, images),
                ).parsed(),
            )

            assertEquals(MutationPostconditionStatus.VERIFIED, verified.status)
            bytesBefore.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(Path.of(path))) }

            val driftProof = ExactRenameProof.of(
                target = oldTarget,
                requiredGeneration = MutationSemanticGeneration(1),
                evidence = RelationshipResultEvidence.Complete(ResultCardinality.Exact(0), completeCoverage),
                occurrences = emptyList(),
            )
            val drift = runCatching {
                backend.verifyMutationPostcondition(
                    MutationPostconditionQuery(
                        MutationPostconditionAuthority.Rename(
                            proof = driftProof,
                            edits = listOf(edits.first()),
                            images = listOf(images.first()),
                        ),
                    ).parsed(),
                )
            }.exceptionOrNull() as? MutationPostconditionFailedException
                ?: error("Expected exact rename occurrence drift to fail")
            assertEquals(listOf(MutationPostconditionLimitation.OCCURRENCE_SET_MISMATCH), drift.limitations)
        }

    @Test
    fun `rename planning rejects incomplete relationship coverage`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            ) to FilePosition(
                filePath = sampleFile.virtualFile.path,
                offset = sampleFile.text.indexOf("greet"),
            )
        }
        val backend = backend(
            workspaceRoot = workspaceRoot,
            relationshipCoverageAuthority = RelationshipCoverageAuthority {
                RelationshipSearchCoverage.limited(
                    RelationshipSearchLimitation.SOURCE_SET_EXCLUDED,
                )
            },
        )

        val failure = runCatching {
            backend.rename(
                RenameQuery(
                    position = position,
                    newName = "welcome",
                ),
            )
        }.exceptionOrNull()

        assertEquals(
            "MUTATION_PROOF_INCOMPLETE",
            (failure as? AnalysisException)?.errorCode,
        )
    }

    @Test
    fun `rename planning returns exact target identity and compiler occurrence count`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            ) to FilePosition(
                filePath = sampleFile.virtualFile.path,
                offset = sampleFile.text.indexOf("greet"),
            )
        }

        val result = backend(workspaceRoot = workspaceRoot).rename(
            RenameQuery(
                position = position,
                newName = "welcome",
            ),
        )

        assertEquals("demo.greet", result.proof.target.fqName)
        assertEquals(SymbolKind.FUNCTION, result.proof.target.kind)
        assertEquals(position.filePath, result.proof.target.declarationFile.value)
        assertEquals(position.offset, result.proof.target.declarationStartOffset.value)
        assertEquals(1, result.proof.evidence.cardinality.totalCount)
        assertEquals(result.proof.evidence.cardinality.totalCount, result.proof.occurrences.size)
        assertTrue(result.proof.occurrences.all { occurrence ->
            occurrence.resolvedTarget == result.proof.target
        })
        assertEquals(result.affectedFiles.toSet(), result.fileImages.map { image -> image.filePath.value }.toSet())
        assertTrue(result.fileImages.all { image -> image.preimage.sha256 != image.postimage.sha256 })
    }

    @Test
    fun `rename planning returns exact BOM CRLF and non-BMP byte images without writing`() = runBlocking {
        ensureProjectReady()
        val file = exactImageRenameFileFixture.get()
        waitUntilIndexesAreReady(project)
        val filePath = Path.of(file.virtualFile.path)
        val before = Files.readAllBytes(filePath)
        assertArrayEquals(exactImageRenameSource.toByteArray(), before)
        val position = readAction {
            FilePosition(filePath.toString(), file.text.indexOf("rawName"))
        }

        val result = backend(
            workspaceRoot = commonWorkspaceRoot(filePath.toString(), hierarchyFile.virtualFile.path),
        ).rename(RenameQuery(position = position, newName = "exactName"))

        val image = result.fileImages.single()
        val expected = exactImageRenameSource.replace("rawName", "exactName").toByteArray()
        assertEquals(filePath.toString(), image.filePath.value)
        assertArrayEquals(before, image.preimage.copyBytes())
        assertArrayEquals(expected, image.postimage.copyBytes())
        assertEquals(FileHashing.sha256(before), result.fileHashes.single().hash)
        assertArrayEquals(before, Files.readAllBytes(filePath), "rename planning must not write")
    }

    @Test
    fun `rename planning rejects an unsaved document instead of omitting exact images`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, position, filePath, before) = readAction {
            val path = Path.of(sampleFile.virtualFile.path)
            RenameImageTestInput(
                workspaceRoot = commonWorkspaceRoot(path.toString(), hierarchyFile.virtualFile.path),
                position = FilePosition(path.toString(), sampleFile.text.indexOf("greet")),
                filePath = path,
                before = Files.readAllBytes(path),
            )
        }
        val document = readAction {
            requireNotNull(FileDocumentManager.getInstance().getDocument(sampleFile.virtualFile))
        }
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.textLength, "\n// unsaved")
            }
        }

        val failure = runCatching {
            backend(workspaceRoot = workspaceRoot).rename(
                RenameQuery(position = position, newName = "welcome"),
            )
        }.exceptionOrNull() as? MutationProofIncompleteException
            ?: error("Expected mutation proof failure")

        assertTrue(RelationshipSearchLimitation.SOURCE_IMAGE_UNPROVEN in failure.evidence.coverage.limitations)
        assertArrayEquals(before, Files.readAllBytes(filePath))
    }

    @Test
    fun `rename planning rejects a changed semantic generation`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, position) = readAction {
            commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            ) to FilePosition(
                filePath = sampleFile.virtualFile.path,
                offset = sampleFile.text.indexOf("greet"),
            )
        }
        val generation = AtomicLong()

        val failure = runCatching {
            backend(
                workspaceRoot = workspaceRoot,
                psiGeneration = generation::incrementAndGet,
            ).rename(
                RenameQuery(
                    position = position,
                    newName = "welcome",
                ),
            )
        }.exceptionOrNull()

        assertEquals("MUTATION_PROOF_INCOMPLETE", (failure as? AnalysisException)?.errorCode)
        assertTrue(
            RelationshipSearchLimitation.GENERATION_CHANGED in
                (failure as MutationProofIncompleteException).evidence.coverage.limitations,
        )
    }

    private data class RenameImageTestInput(
        val workspaceRoot: Path,
        val position: FilePosition,
        val filePath: Path,
        val before: ByteArray,
    )

    @Test
    fun `relationship queries fail closed when source set coverage is excluded`() = runBlocking {
        ensureProjectReady()
        val inputs = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            val greetOffset = sampleFile.text.indexOf("greet")
            RelationshipCoverageTestInputs(
                workspaceRoot = root,
                greetPosition = FilePosition(sampleFile.virtualFile.path, greetOffset),
                greetSelector = KastExactSymbolSelector(
                    fqName = "demo.greet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = greetOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                shapeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        val excludedCoverage = RelationshipSearchCoverage.limited(
            RelationshipSearchLimitation.SOURCE_SET_EXCLUDED,
        )
        val backend = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = RelationshipCoverageAuthority { excludedCoverage },
        )

        val references = backend.findReferences(ReferencesQuery(position = inputs.greetPosition))
        val referenceEvidence = when (val evidence = references.evidence) {
            is RelationshipResultEvidence.Limited -> evidence
            is RelationshipResultEvidence.Complete,
            is RelationshipResultEvidence.Resumable,
            -> error("Expected limited reference evidence, got $evidence")
        }
        assertEquals(ResultCardinality.KnownMinimum(references.references.size), referenceEvidence.cardinality)
        assertEquals(listOf(RelationshipSearchLimitation.SOURCE_SET_EXCLUDED), referenceEvidence.coverage.limitations)

        val callers = backend.callRelations(
            KastCallersQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        )
        val implementations = backend.implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                maxResults = 4,
            ),
        )
        val hierarchy = backend.hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        )

        assertTrue(callers is CallRelationsResult.Limited)
        assertTrue(implementations is ImplementationRelationsResult.Limited)
        assertTrue(hierarchy is HierarchyRelationsResult.Limited)
    }

    @Test
    fun `persisted limited relationship outcome degrades reference adapter evidence`() = runBlocking {
        ensureProjectReady()
        val (workspaceRoot, filePath, offset) = readAction {
            Triple(
                commonWorkspaceRoot(sampleFile.virtualFile.path, hierarchyFile.virtualFile.path),
                sampleFile.virtualFile.path,
                sampleFile.text.indexOf("greet"),
            )
        }

        SqliteSourceIndexStore(workspaceRoot).use { store ->
            store.ensureSchema()
            store.reconcileFileInventory(
                entries = listOf(
                    fileInventoryEntry(
                        workspaceRoot = workspaceRoot,
                        path = filePath,
                        lastModifiedMillis = 1,
                        contentHash = FileContentHash.parse("a".repeat(64)),
                        moduleName = ":main[main]",
                        sourceSet = "main",
                    ),
                ),
                versions = FileStageVersions.CURRENT,
            )
            store.commitRelationshipBatch(
                listOf(
                    RelationshipFileStageUpdate(
                        work = store.pendingFileStages(FileIndexStage.RELATIONSHIPS).single(),
                        scannedContentHash = store.pendingFileStages(FileIndexStage.RELATIONSHIPS)
                            .single()
                            .contentHash,
                        references = emptyList(),
                        declarations = emptyList(),
                        limitations = listOf(FileStageLimitation.UNRESOLVED_RELATIONSHIP),
                    ),
                ),
            )

            val result = backend(
                workspaceRoot = workspaceRoot,
                relationshipCoverageAuthority = relationshipCoverageAuthority(sourceIndexStore = store),
            ).findReferences(ReferencesQuery(position = FilePosition(filePath, offset)))

            val evidence = result.evidence as RelationshipResultEvidence.Limited
            assertTrue(RelationshipSearchLimitation.BACKEND_INCOMPLETE in evidence.coverage.limitations)
            assertFalse(result.searchScope?.exhaustive ?: true)
            assertEquals(SearchScope.CandidateCoverage.PARTIAL, result.searchScope?.candidateCoverage)
        }
    }

    @Test
    fun `relationship queries fail closed when the backend root does not match the exact selector`() = runBlocking {
        ensureProjectReady()
        val inputs = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            val greetOffset = sampleFile.text.indexOf("greet")
            RelationshipCoverageTestInputs(
                workspaceRoot = root,
                greetPosition = FilePosition(sampleFile.virtualFile.path, greetOffset),
                greetSelector = KastExactSymbolSelector(
                    fqName = "demo.notGreet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = greetOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                shapeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.NotShape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        val backend = backend(inputs.workspaceRoot)

        val callers = backend.callRelations(
            KastCallersQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        ) as CallRelationsResult.Limited
        val implementations = backend.implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                maxResults = 4,
            ),
        ) as ImplementationRelationsResult.Limited
        val hierarchy = backend.hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        ) as HierarchyRelationsResult.Limited

        listOf(callers.evidence, implementations.evidence, hierarchy.evidence).forEach { evidence ->
            assertTrue(RelationshipSearchLimitation.IDENTITY_UNPROVEN in evidence.coverage.limitations)
        }
    }

    @Test
    fun `relationship queries reassess coverage in the final commit epoch`() = runBlocking {
        ensureProjectReady()
        val inputs = readAction {
            val root = commonWorkspaceRoot(
                sampleFile.virtualFile.path,
                hierarchyFile.virtualFile.path,
            )
            val greetOffset = sampleFile.text.indexOf("greet")
            RelationshipCoverageTestInputs(
                workspaceRoot = root,
                greetPosition = FilePosition(sampleFile.virtualFile.path, greetOffset),
                greetSelector = KastExactSymbolSelector(
                    fqName = "demo.greet",
                    declarationFile = sampleFile.virtualFile.path,
                    declarationStartOffset = greetOffset,
                    kind = SymbolKind.FUNCTION,
                ),
                shapeSelector = KastExactSymbolSelector(
                    fqName = "demo.hierarchy.Shape",
                    declarationFile = hierarchyFile.virtualFile.path,
                    declarationStartOffset = hierarchyFile.text.indexOf("Shape"),
                    kind = SymbolKind.INTERFACE,
                ),
            )
        }
        fun changingAuthority(): RelationshipCoverageAuthority {
            val assessments = AtomicInteger()
            return RelationshipCoverageAuthority {
                if (assessments.getAndIncrement() == 0) {
                    RelationshipSearchCoverage.complete()
                } else {
                    RelationshipSearchCoverage.limited(RelationshipSearchLimitation.INDEX_NOT_READY)
                }
            }
        }

        val callers = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = changingAuthority(),
        ).callRelations(
            KastCallersQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.greetSelector,
                direction = WrapperCallDirection.INCOMING,
                depth = 1,
                maxResults = 4,
            ),
        )
        val implementations = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = changingAuthority(),
        ).implementationRelations(
            KastImplementationsQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                maxResults = 4,
            ),
        )
        val hierarchy = backend(
            workspaceRoot = inputs.workspaceRoot,
            relationshipCoverageAuthority = changingAuthority(),
        ).hierarchyRelations(
            KastHierarchyQuery(
                workspaceRoot = inputs.workspaceRoot.toString(),
                selector = inputs.shapeSelector,
                direction = TypeHierarchyDirection.SUBTYPES,
                depth = 1,
                maxResults = 4,
            ),
        )

        assertTrue(callers is CallRelationsResult.Limited)
        assertTrue(implementations is ImplementationRelationsResult.Limited)
        assertTrue(hierarchy is HierarchyRelationsResult.Limited)
    }

    @Test
    fun `capabilities read backend version from generated resource`() = runBlocking {
        ensureProjectReady()

        val expectedVersion = KastIndexerBackend::class.java
            .getResource("/kast-indexer-version.txt")
            ?.readText()
            ?.trim()

        assertNotNull(expectedVersion)
        assertEquals(expectedVersion, backend().capabilities().backendVersion)
    }
}
