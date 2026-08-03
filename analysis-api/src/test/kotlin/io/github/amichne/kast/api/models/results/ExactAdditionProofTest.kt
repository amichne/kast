package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.result.AddFileTargetState
import io.github.amichne.kast.api.contract.result.AdditionClasspathFingerprint
import io.github.amichne.kast.api.contract.result.AdditionCollisionDimension
import io.github.amichne.kast.api.contract.result.AdditionCompilerTargetSignature
import io.github.amichne.kast.api.contract.result.AdditionDeclarationCollisionSignature
import io.github.amichne.kast.api.contract.result.AdditionGradleBuildRoot
import io.github.amichne.kast.api.contract.result.AdditionGradleProjectPath
import io.github.amichne.kast.api.contract.result.AdditionGradleSourceSetName
import io.github.amichne.kast.api.contract.result.AdditionIdeaModuleName
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionNewlinePolicy
import io.github.amichne.kast.api.contract.result.AdditionOccurrenceProvenance
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionProjectModelFingerprint
import io.github.amichne.kast.api.contract.result.AdditionRebindingUnresolvedReason
import io.github.amichne.kast.api.contract.result.AdditionRebindingDimension
import io.github.amichne.kast.api.contract.result.AdditionResolvedTarget
import io.github.amichne.kast.api.contract.result.AdditionSourceRoot
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclarationKind
import io.github.amichne.kast.api.contract.result.CompilerFileBottomInsertion
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.ExactAdditionContextFileHash
import io.github.amichne.kast.api.contract.result.ExactAdditionCollisionEvidence
import io.github.amichne.kast.api.contract.result.ExactAdditionOutboundEvidence
import io.github.amichne.kast.api.contract.result.ExactAdditionOutboundOccurrence
import io.github.amichne.kast.api.contract.result.ExactAdditionProofContext
import io.github.amichne.kast.api.contract.result.ExactAdditionRebindingBaseline
import io.github.amichne.kast.api.contract.result.ExactAdditionRebindingOccurrence
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.protocol.AdditionProofIncompleteException
import io.github.amichne.kast.api.protocol.AdditionProofLimitation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactAdditionProofTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `add file proof snapshots ordered declarations and complete occurrence evidence`() {
        val declarations = mutableListOf(
            declaration("Added", AdditionTopLevelDeclarationKind.CLASS, 15, 26, "1"),
            declaration("greet", AdditionTopLevelDeclarationKind.FUNCTION, 28, 52, "2"),
        )
        val proof = addFileProof(declarations)
        declarations.clear()

        assertEquals(AddFileTargetState.ABSENT, proof.targetState)
        assertEquals(listOf("Added", "greet"), proof.declarations.map { it.name })
        assertEquals(AdditionCollisionDimension.entries, proof.collisionEvidence.dimensions)
        assertEquals(2, proof.outboundEvidence.cardinality.value)
        assertEquals(
            setOf(AdditionResolvedTarget.Source::class, AdditionResolvedTarget.External::class),
            proof.outboundEvidence.occurrences.map { it.resolvedTarget::class }.toSet(),
        )
        assertEquals(
            setOf(AdditionOccurrenceProvenance.COMPILER),
            proof.outboundEvidence.occurrences.map { it.provenance }.toSet(),
        )
        assertEquals(0, proof.rebindingBaseline.cardinality.value)
        assertEquals(AdditionRebindingDimension.entries, proof.rebindingBaseline.dimensions)
        assertEquals(emptyList<ExactAdditionRebindingOccurrence>(), proof.rebindingBaseline.occurrences)
        assertNotSame(declarations, proof.declarations)

        val encoded = json.encodeToString(ExactAddFileProof.serializer(), proof)
        val decoded = json.decodeFromString(ExactAddFileProof.serializer(), encoded)

        assertEquals(proof.targetPath, decoded.targetPath)
        assertEquals(proof.owner, decoded.owner)
        assertEquals(proof.declarations, decoded.declarations)
        assertEquals(proof.outboundEvidence, decoded.outboundEvidence)
        assertEquals(proof.rebindingBaseline, decoded.rebindingBaseline)
        assertEquals(encoded, json.encodeToString(ExactAddFileProof.serializer(), decoded))
    }

    @Test
    fun `add declaration proof carries one declaration prehash file bottom and newline policy`() {
        val proof = addDeclarationProof()

        assertEquals("Added", proof.declaration.name)
        assertEquals(42, proof.insertion.offset.value)
        assertEquals(
            AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
            proof.newlinePolicy,
        )

        val encoded = json.encodeToString(ExactAddDeclarationProof.serializer(), proof)
        val decoded = json.decodeFromString(ExactAddDeclarationProof.serializer(), encoded)

        assertEquals(proof.targetPreimageSha256, decoded.targetPreimageSha256)
        assertEquals(proof.declaration, decoded.declaration)
        assertEquals(proof.insertion, decoded.insertion)
        assertEquals(encoded, json.encodeToString(ExactAddDeclarationProof.serializer(), decoded))
    }

    @Test
    fun `factories reject package declaration range and target hash inconsistencies`() {
        val emptyAddition = assertThrows(AdditionProofIncompleteException::class.java) {
            addFileProof(emptyList())
        }
        assertEquals(listOf(AdditionProofLimitation.ZERO_DECLARATIONS), emptyAddition.limitations)
        assertThrows(IllegalArgumentException::class.java) {
            addFileProof(
                listOf(
                    declaration("first", AdditionTopLevelDeclarationKind.FUNCTION, 10, 30, "1"),
                    declaration("second", AdditionTopLevelDeclarationKind.FUNCTION, 20, 40, "2"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactAddFileProof.of(
                targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Added.kt"),
                owner = owner(),
                packageIdentity = packageIdentity(),
                declarations = listOf(
                    AdditionTopLevelDeclaration.of(
                        packageIdentity = AdditionKotlinPackage.Named.of("other"),
                        name = "Added",
                        kind = AdditionTopLevelDeclarationKind.CLASS,
                        relativeStartOffset = 0,
                        relativeEndOffset = 12,
                        collisionSignature = AdditionDeclarationCollisionSignature.of("1".repeat(64)),
                    ),
                ),
                context = addFileContext(),
                collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
                outboundEvidence = outboundEvidence(),
                rebindingBaseline = zeroRebindingBaseline(),
                postimageSha256 = AdditionPostimageSha256.of("8".repeat(64)),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactAddDeclarationProof.of(
                targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Existing.kt"),
                targetPreimageSha256 = AdditionTargetPreimageSha256.of("f".repeat(64)),
                owner = owner(),
                packageIdentity = packageIdentity(),
                declaration = declaration("Added", AdditionTopLevelDeclarationKind.CLASS, 0, 12, "1"),
                insertion = CompilerFileBottomInsertion.at(42),
                newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
                context = addDeclarationContext(),
                collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
                outboundEvidence = outboundEvidence(),
                rebindingBaseline = zeroRebindingBaseline(),
                postimageSha256 = AdditionPostimageSha256.of("a".repeat(64)),
            )
        }
        val duplicateRange = ExactAdditionRebindingOccurrence.unresolved(
            filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
            startOffset = 30,
            endOffset = 35,
            reason = AdditionRebindingUnresolvedReason.NOT_FOUND,
        )
        assertThrows(IllegalArgumentException::class.java) {
            ExactAdditionRebindingBaseline.complete(listOf(duplicateRange, duplicateRange))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactAdditionOutboundEvidence.complete(
                listOf(
                    ExactAdditionOutboundOccurrence.of(5, 12, sourceTarget()),
                    ExactAdditionOutboundOccurrence.of(10, 14, sourceTarget()),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExactAdditionRebindingBaseline.complete(
                listOf(
                    ExactAdditionRebindingOccurrence.unresolved(
                        filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                        startOffset = 5,
                        endOffset = 12,
                        reason = AdditionRebindingUnresolvedReason.NOT_FOUND,
                    ),
                    ExactAdditionRebindingOccurrence.unresolved(
                        filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                        startOffset = 10,
                        endOffset = 14,
                        reason = AdditionRebindingUnresolvedReason.AMBIGUOUS,
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            addFileProof(rebindingBaseline = nonZeroRebindingBaseline())
        }
        assertThrows(IllegalArgumentException::class.java) {
            addDeclarationProof(rebindingBaseline = nonZeroRebindingBaseline())
        }
    }

    @Test
    fun `deserialization rejects malformed limited duplicate ambiguous and inconsistent proof state`() {
        val valid = json.parseToJsonElement(
            json.encodeToString(ExactAddFileProof.serializer(), addFileProof()),
        ).jsonObject
        val context = valid.getValue("context").jsonObject
        val baseline = valid.getValue("rebindingBaseline").jsonObject
        val collision = valid.getValue("collisionEvidence").jsonObject
        val declarations = valid.getValue("declarations").jsonArray

        val malformed = mapOf(
            "uppercase hash" to valid.replacing(
                "context",
                context.replacing("projectModelFingerprint", JsonPrimitive("A".repeat(64))),
            ),
            "relative path" to valid.replacing("targetPath", JsonPrimitive("src/Added.kt")),
            "package mismatch" to valid.replacing(
                "packageIdentity",
                JsonObject(mapOf("type" to JsonPrimitive("ROOT"))),
            ),
            "duplicate declaration" to valid.replacing(
                "declarations",
                JsonArray(listOf(declarations.first(), declarations.first())),
            ),
            "cardinality drift" to valid.replacing(
                "rebindingBaseline",
                baseline.replacing("cardinality", JsonPrimitive(7)),
            ),
            "non-zero rebinding baseline" to valid.replacing(
                "rebindingBaseline",
                json.parseToJsonElement(
                    json.encodeToString(
                        ExactAdditionRebindingBaseline.serializer(),
                        nonZeroRebindingBaseline(),
                    ),
                ),
            ),
            "limited dimension" to valid.replacing(
                "rebindingBaseline",
                baseline.replacing("dimensions", JsonArray(listOf(JsonPrimitive("LIMITED")))),
            ),
            "collision dimension incomplete" to valid.replacing(
                "collisionEvidence",
                collision.replacing("dimensions", JsonArray(listOf(JsonPrimitive("LIMITED")))),
            ),
            "ambiguous owner" to valid.replacing(
                "owner",
                JsonArray(listOf(valid.getValue("owner"), valid.getValue("owner"))),
            ),
        )

        malformed.forEach { (case, payload) ->
            assertThrows(
                IllegalArgumentException::class.java,
                { json.decodeFromString(ExactAddFileProof.serializer(), payload.toString()) },
                case,
            )
        }

        val declarationProof = json.parseToJsonElement(
            json.encodeToString(ExactAddDeclarationProof.serializer(), addDeclarationProof()),
        ).jsonObject
        val declarationCardinality = declarationProof.toMutableMap().apply {
            remove("declaration")
            put("declarations", JsonArray(emptyList()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(
                ExactAddDeclarationProof.serializer(),
                JsonObject(declarationCardinality).toString(),
            )
        }
        val declarationNonZeroBaseline = declarationProof.replacing(
            "rebindingBaseline",
            json.parseToJsonElement(
                json.encodeToString(
                    ExactAdditionRebindingBaseline.serializer(),
                    nonZeroRebindingBaseline(),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(
                ExactAddDeclarationProof.serializer(),
                declarationNonZeroBaseline.toString(),
            )
        }
    }

    @Test
    fun `incomplete addition proof has a closed sorted limitation set`() {
        val exception = AdditionProofIncompleteException.of(
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            AdditionProofLimitation.GENERATION_CHANGED,
            AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
        )

        assertEquals(
            listOf(
                AdditionProofLimitation.GENERATION_CHANGED,
                AdditionProofLimitation.SOURCE_CONTEXT_CHANGED,
            ),
            exception.limitations,
        )
        assertEquals("ADDITION_PROOF_INCOMPLETE", exception.errorCode)
        assertEquals(true, exception.retryable)
        assertThrows(IllegalArgumentException::class.java) {
            AdditionProofIncompleteException.of()
        }
    }

    private fun addFileProof(
        declarations: List<AdditionTopLevelDeclaration> = listOf(
            declaration("Added", AdditionTopLevelDeclarationKind.CLASS, 15, 26, "1"),
        ),
        rebindingBaseline: ExactAdditionRebindingBaseline = zeroRebindingBaseline(),
    ): ExactAddFileProof = ExactAddFileProof.of(
        targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Added.kt"),
        owner = owner(),
        packageIdentity = packageIdentity(),
        declarations = declarations,
        context = addFileContext(),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(declarations.size),
        outboundEvidence = outboundEvidence(),
        rebindingBaseline = rebindingBaseline,
        postimageSha256 = AdditionPostimageSha256.of("8".repeat(64)),
    )

    private fun addDeclarationProof(
        rebindingBaseline: ExactAdditionRebindingBaseline = zeroRebindingBaseline(),
    ): ExactAddDeclarationProof = ExactAddDeclarationProof.of(
        targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Existing.kt"),
        targetPreimageSha256 = AdditionTargetPreimageSha256.of("9".repeat(64)),
        owner = owner(),
        packageIdentity = packageIdentity(),
        declaration = declaration("Added", AdditionTopLevelDeclarationKind.CLASS, 0, 12, "1"),
        insertion = CompilerFileBottomInsertion.at(42),
        newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
        context = addDeclarationContext(),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = outboundEvidence(),
        rebindingBaseline = rebindingBaseline,
        postimageSha256 = AdditionPostimageSha256.of("a".repeat(64)),
    )

    private fun owner() = io.github.amichne.kast.api.contract.result.AdditionSourceOwner.of(
        sourceRoot = AdditionSourceRoot.parse("/workspace/src/main/kotlin"),
        ideaModuleName = AdditionIdeaModuleName.of("root.main"),
        gradleBuildRoot = AdditionGradleBuildRoot.parse("/workspace"),
        gradleProjectPath = AdditionGradleProjectPath.parse(":"),
        sourceSetName = AdditionGradleSourceSetName.of("main"),
    )

    private fun packageIdentity(): AdditionKotlinPackage = AdditionKotlinPackage.Named.of("sample")

    private fun declaration(
        name: String,
        kind: AdditionTopLevelDeclarationKind,
        startOffset: Int,
        endOffset: Int,
        signaturePrefix: String,
    ): AdditionTopLevelDeclaration = AdditionTopLevelDeclaration.of(
        packageIdentity = packageIdentity(),
        name = name,
        kind = kind,
        relativeStartOffset = startOffset,
        relativeEndOffset = endOffset,
        collisionSignature = AdditionDeclarationCollisionSignature.of(signaturePrefix.repeat(64)),
    )

    private fun addFileContext(): ExactAdditionProofContext = ExactAdditionProofContext.of(
        requiredGeneration = MutationSemanticGeneration(7),
        projectModelFingerprint = AdditionProjectModelFingerprint.of("3".repeat(64)),
        classpathFingerprint = AdditionClasspathFingerprint.of("4".repeat(64)),
        contextFileHashes = listOf(
            ExactAdditionContextFileHash.of(
                filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                sha256 = "5".repeat(64),
            ),
            ExactAdditionContextFileHash.of(
                filePath = "/workspace/src/main/kotlin/sample/Helper.kt",
                sha256 = "6".repeat(64),
            ),
        ),
    )

    private fun addDeclarationContext(): ExactAdditionProofContext = ExactAdditionProofContext.of(
        requiredGeneration = MutationSemanticGeneration(7),
        projectModelFingerprint = AdditionProjectModelFingerprint.of("3".repeat(64)),
        classpathFingerprint = AdditionClasspathFingerprint.of("4".repeat(64)),
        contextFileHashes = listOf(
            ExactAdditionContextFileHash.of(
                filePath = "/workspace/src/main/kotlin/sample/Existing.kt",
                sha256 = "9".repeat(64),
            ),
            ExactAdditionContextFileHash.of(
                filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                sha256 = "5".repeat(64),
            ),
            ExactAdditionContextFileHash.of(
                filePath = "/workspace/src/main/kotlin/sample/Helper.kt",
                sha256 = "6".repeat(64),
            ),
        ),
    )

    private fun outboundEvidence(): ExactAdditionOutboundEvidence =
        ExactAdditionOutboundEvidence.complete(
            listOf(
                ExactAdditionOutboundOccurrence.of(
                    relativeStartOffset = 5,
                    relativeEndOffset = 11,
                    resolvedTarget = sourceTarget(),
                ),
                ExactAdditionOutboundOccurrence.of(
                    relativeStartOffset = 40,
                    relativeEndOffset = 46,
                    resolvedTarget = AdditionResolvedTarget.External.of(
                        fqName = "kotlin.String",
                        kind = SymbolKind.CLASS,
                        compilerSignature = AdditionCompilerTargetSignature.of("class|kotlin.String"),
                    ),
                ),
            ),
        )

    private fun zeroRebindingBaseline(): ExactAdditionRebindingBaseline =
        ExactAdditionRebindingBaseline.complete(emptyList())

    private fun nonZeroRebindingBaseline(): ExactAdditionRebindingBaseline =
        ExactAdditionRebindingBaseline.complete(
            listOf(
                ExactAdditionRebindingOccurrence.resolved(
                    filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                    startOffset = 10,
                    endOffset = 16,
                    target = sourceTarget(),
                ),
                ExactAdditionRebindingOccurrence.unresolved(
                    filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                    startOffset = 30,
                    endOffset = 35,
                    reason = AdditionRebindingUnresolvedReason.NOT_FOUND,
                ),
            ),
        )

    private fun sourceTarget(): AdditionResolvedTarget = AdditionResolvedTarget.Source.of(
        SymbolIdentity(
            fqName = "sample.Helper",
            kind = SymbolKind.CLASS,
            declarationFile = NormalizedPath.parse("/workspace/src/main/kotlin/sample/Helper.kt"),
            declarationStartOffset = NonNegativeInt(0),
        ),
    )

    private fun JsonObject.replacing(name: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { put(name, value) })
}
