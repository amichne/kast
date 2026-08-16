package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.result.*
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.api.validation.ParsedMutationPostconditionAuthority
import io.github.amichne.kast.api.validation.parsed
import io.github.amichne.kast.api.protocol.ValidationException
import io.github.amichne.kast.api.protocol.MutationPostconditionFailedException
import io.github.amichne.kast.api.protocol.MutationPostconditionLimitation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MutationPostconditionContractTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `add file verifier authority retains only proof and exact postimage`() {
        val content = "package sample\n\nclass Added\n"
        val postimage = ExactByteImage.of(content.toByteArray())
        val proof = addFileProof(content)

        val parsed = MutationPostconditionQuery(
            MutationPostconditionAuthority.AddFile(proof, postimage),
        ).parsed()

        val authority = parsed.authority as ParsedMutationPostconditionAuthority.AddFile
        assertEquals(proof, authority.proof)
        assertEquals(postimage, authority.postimage)
    }

    @Test
    fun `add file verifier authority rejects a substituted postimage before semantic work`() {
        val content = "package sample\n\nclass Added\n"

        assertThrows(ValidationException::class.java) {
            MutationPostconditionQuery(
                MutationPostconditionAuthority.AddFile(
                    proof = addFileProof(content),
                    postimage = ExactByteImage.of((content + " ").toByteArray()),
                ),
            ).parsed()
        }
    }

    @Test
    fun `addition verifier authorities reject malformed UTF-8 as validation failures`() {
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        val addFileImage = ExactByteImage.of(malformed)
        assertThrows(ValidationException::class.java) {
            MutationPostconditionQuery(
                MutationPostconditionAuthority.AddFile(
                    proof = addFileProof(malformed),
                    postimage = addFileImage,
                ),
            ).parsed()
        }

        val target = "/workspace/src/main/kotlin/sample/Existing.kt"
        val preimage = "package sample\n\nclass Existing\n".toByteArray()
        val postimage = preimage + malformed
        val image = ExactFileImage.of(target, preimage, postimage)
        assertThrows(ValidationException::class.java) {
            MutationPostconditionQuery(
                MutationPostconditionAuthority.AddDeclaration(
                    proof = addDeclarationProof(target, preimage, image),
                    image = image,
                ),
            ).parsed()
        }
    }

    @Test
    fun `addition verifier authorities reject persisted non-zero rebinding baselines`() {
        val addFileContent = "package sample\n\nclass Added\n"
        val addFileQuery = MutationPostconditionQuery(
            MutationPostconditionAuthority.AddFile(
                proof = addFileProof(addFileContent),
                postimage = ExactByteImage.of(addFileContent.toByteArray()),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(
                MutationPostconditionQuery.serializer(),
                withNonZeroRebindingBaseline(addFileQuery).toString(),
            )
        }

        val target = "/workspace/src/main/kotlin/sample/Existing.kt"
        val preimage = "package sample\n\nclass Existing\n".toByteArray()
        val postimage = preimage + "\nclass Added\n".toByteArray()
        val image = ExactFileImage.of(target, preimage, postimage)
        val addDeclarationQuery = MutationPostconditionQuery(
            MutationPostconditionAuthority.AddDeclaration(
                proof = addDeclarationProof(target, preimage, image),
                image = image,
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(
                MutationPostconditionQuery.serializer(),
                withNonZeroRebindingBaseline(addDeclarationQuery).toString(),
            )
        }
    }

    @Test
    fun `rename verifier authority rejects a postimage not derived from its UTF-16 edits`() {
        val path = "/workspace/src/main/kotlin/sample/Sample.kt"
        val preimage = "fun greet() = Unit\n".toByteArray()
        val edit = TextEdit(path, 4, 9, "welcome")
        val unrelatedPostimage = "fun welcome() = Unit\n// unrelated\n".toByteArray()

        assertThrows(ValidationException::class.java) {
            MutationPostconditionQuery(
                MutationPostconditionAuthority.Rename(
                    proof = renameProof(path, edit),
                    edits = listOf(edit),
                    images = listOf(ExactFileImage.of(path, preimage, unrelatedPostimage)),
                ),
            ).parsed()
        }
    }

    @Test
    fun `replacement verifier authority rejects a postimage not derived from its UTF-16 edit`() {
        val path = "/workspace/src/main/kotlin/sample/Sample.kt"
        val source = "fun greet(): Int = 1\n"
        val preimage = source.toByteArray()
        val proposedDeclaration = "fun greet(): Int = 2"
        val bodyStart = source.indexOf('1')
        val edit = TextEdit(path, bodyStart, bodyStart + 1, "2")
        val unrelatedPostimage = "fun greet(): Int = 2\n// unrelated\n".toByteArray()

        assertThrows(ValidationException::class.java) {
            MutationPostconditionQuery(
                MutationPostconditionAuthority.Replacement(
                    proof = replacementProof(path, preimage, edit, proposedDeclaration),
                    edit = edit,
                    images = listOf(ExactFileImage.of(path, preimage, unrelatedPostimage)),
                ),
            ).parsed()
        }
    }

    @Test
    fun `verified result rejects contradictory addition evidence and nondeterministic postimages`() {
        val proof = addFileProof("package sample\n\nclass Added\n")
        assertThrows(IllegalArgumentException::class.java) {
            MutationPostconditionEvidence.AddFile(
                owner = proof.owner,
                packageIdentity = AdditionKotlinPackage.Root,
                declarations = proof.declarations,
                outboundEvidence = proof.outboundEvidence,
            )
        }

        val evidence = MutationPostconditionEvidence.AddFile(
            owner = proof.owner,
            packageIdentity = proof.packageIdentity,
            declarations = proof.declarations,
            outboundEvidence = proof.outboundEvidence,
        )
        assertThrows(IllegalArgumentException::class.java) {
            MutationPostconditionResult.verified(
                operation = MutationPostconditionOperation.ADD_FILE,
                currentGeneration = MutationSemanticGeneration(8),
                postimages = listOf(
                    VerifiedMutationPostimage(ExactFileImagePath("/workspace/z.kt"), ExactByteImage.of("z".toByteArray()).sha256),
                    VerifiedMutationPostimage(ExactFileImagePath("/workspace/a.kt"), ExactByteImage.of("a".toByteArray()).sha256),
                ),
                evidence = evidence,
            )
        }
    }

    @Test
    fun `semantic source unavailability is a retryable postcondition admission failure`() {
        val unavailable = MutationPostconditionFailedException.of(
            MutationPostconditionLimitation.SEMANTIC_SOURCE_UNAVAILABLE,
        )
        val mismatch = MutationPostconditionFailedException.of(
            MutationPostconditionLimitation.POSTIMAGE_MISMATCH,
        )

        assertEquals(true, unavailable.retryable)
        assertEquals(false, mismatch.retryable)
    }

    private fun addFileProof(content: String): ExactAddFileProof = ExactAddFileProof.of(
        targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Added.kt"),
        owner = AdditionSourceOwner.of(
            sourceRoot = AdditionSourceRoot.parse("/workspace/src/main/kotlin"),
            ideaModuleName = AdditionIdeaModuleName.of("main"),
            gradleBuildRoot = AdditionGradleBuildRoot.parse("/workspace"),
            gradleProjectPath = AdditionGradleProjectPath.parse(":"),
            sourceSetName = AdditionGradleSourceSetName.of("main"),
        ),
        packageIdentity = AdditionKotlinPackage.Named.of("sample"),
        declarations = listOf(
            AdditionTopLevelDeclaration.of(
                packageIdentity = AdditionKotlinPackage.Named.of("sample"),
                name = "Added",
                kind = AdditionTopLevelDeclarationKind.CLASS,
                relativeStartOffset = 16,
                relativeEndOffset = 27,
                collisionSignature = AdditionDeclarationCollisionSignature.of("1".repeat(64)),
            ),
        ),
        context = ExactAdditionProofContext.of(
            requiredGeneration = MutationSemanticGeneration(7),
            projectModelFingerprint = AdditionProjectModelFingerprint.of("2".repeat(64)),
            classpathFingerprint = AdditionClasspathFingerprint.of("3".repeat(64)),
            contextFileHashes = emptyList(),
        ),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
        rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
        postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(content.toByteArray())),
    )

    private fun renameProof(path: String, declarationEdit: TextEdit): ExactRenameProof {
        val target = SymbolIdentity(
            fqName = "sample.greet",
            kind = SymbolKind.FUNCTION,
            declarationFile = NormalizedPath.parse(path),
            declarationStartOffset = NonNegativeInt(declarationEdit.startOffset),
        )
        return ExactRenameProof.of(
            target = target,
            requiredGeneration = MutationSemanticGeneration(7),
            evidence = RelationshipResultEvidence.Complete(
                cardinality = ResultCardinality.Exact(0),
                coverage = RelationshipSearchCoverage.complete(),
            ),
            occurrences = emptyList(),
        )
    }

    private fun replacementProof(
        path: String,
        preimage: ByteArray,
        edit: TextEdit,
        proposedDeclaration: String,
    ): ExactReplacementProof {
        val signature = ReplacementFunctionSignature.of(
            name = "greet",
            receiverType = null,
            contextReceiverTypes = emptyList(),
            typeParameters = emptyList(),
            valueParameters = emptyList(),
            returnType = "kotlin.Int",
            visibility = ReplacementVisibility.PUBLIC,
            modality = ReplacementModality.FINAL,
            hasStableParameterNames = true,
            suspend = false,
            operator = false,
            inline = false,
            override = false,
            infix = false,
            static = false,
            tailrec = false,
            external = false,
            expect = false,
            actual = false,
        )
        return admitted(ExactReplacementProof.admit(
            target = SymbolIdentity(
                fqName = "sample.greet",
                kind = SymbolKind.FUNCTION,
                declarationFile = NormalizedPath.parse(path),
                declarationStartOffset = NonNegativeInt(4),
            ),
            requiredGeneration = MutationSemanticGeneration(7),
            sourceRange = Location(
                filePath = path,
                startOffset = edit.startOffset,
                endOffset = edit.endOffset,
                startLine = 1,
                startColumn = 1,
                preview = preimage.toString(Charsets.UTF_8).trimEnd(),
            ),
            fileHashes = listOf(FileHash(path, FileHashing.sha256(preimage))),
            compilerContext = ReplacementCompilerContext.of(
                emptyMap(),
                admitted(ReplacementCompilerModelGeneration.parse(1)),
            ),
            oldSignature = signature,
            proposedSignature = signature,
            proposedDeclarationHash = admitted(
                ReplacementDeclarationSha256.parse(FileHashing.sha256(proposedDeclaration)),
            ),
            proposedDeclarationLength = proposedDeclaration.length,
            proposedBodyHash = admitted(ReplacementBodySha256.parse(FileHashing.sha256(edit.newText))),
            proposedBodyLength = edit.newText.length,
            declarationSlice = admitted(
                ReplacementDeclarationSlice.of(
                    NonNegativeInt(0),
                    NonNegativeInt(proposedDeclaration.length),
                ),
            ),
            proposedBodySlice = admitted(
                ReplacementSubmittedBodySlice.of(
                    NonNegativeInt(proposedDeclaration.indexOf(edit.newText)),
                    NonNegativeInt(proposedDeclaration.indexOf(edit.newText) + edit.newText.length),
                ),
            ),
            evidence = ReplacementOutboundEvidence.Complete.of(0),
            outboundReferences = emptyList(),
        ))
    }

    private fun <Value> admitted(admission: ReplacementContractAdmission<Value>): Value =
        (admission as ReplacementContractAdmission.Admitted).value

    private fun addFileProof(content: ByteArray): ExactAddFileProof = ExactAddFileProof.of(
        targetPath = AdditionTargetPath.parse("/workspace/src/main/kotlin/sample/Added.kt"),
        owner = owner(),
        packageIdentity = AdditionKotlinPackage.Named.of("sample"),
        declarations = listOf(declaration()),
        context = context(),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
        rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
        postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(content)),
    )

    private fun addDeclarationProof(
        target: String,
        preimage: ByteArray,
        image: ExactFileImage,
    ): ExactAddDeclarationProof = ExactAddDeclarationProof.of(
        targetPath = AdditionTargetPath.parse(target),
        targetPreimageSha256 = AdditionTargetPreimageSha256.of(FileHashing.sha256(preimage)),
        owner = owner(),
        packageIdentity = AdditionKotlinPackage.Named.of("sample"),
        declaration = declaration(),
        insertion = CompilerFileBottomInsertion.at(preimage.toString(Charsets.UTF_8).length),
        newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
        context = context(ExactAdditionContextFileHash.of(target, FileHashing.sha256(preimage))),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
        rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
        postimageSha256 = AdditionPostimageSha256.of(image.postimage.sha256.value),
    )

    private fun owner(): AdditionSourceOwner = AdditionSourceOwner.of(
        sourceRoot = AdditionSourceRoot.parse("/workspace/src/main/kotlin"),
        ideaModuleName = AdditionIdeaModuleName.of("main"),
        gradleBuildRoot = AdditionGradleBuildRoot.parse("/workspace"),
        gradleProjectPath = AdditionGradleProjectPath.parse(":"),
        sourceSetName = AdditionGradleSourceSetName.of("main"),
    )

    private fun declaration(): AdditionTopLevelDeclaration = AdditionTopLevelDeclaration.of(
        packageIdentity = AdditionKotlinPackage.Named.of("sample"),
        name = "Added",
        kind = AdditionTopLevelDeclarationKind.CLASS,
        relativeStartOffset = 0,
        relativeEndOffset = 1,
        collisionSignature = AdditionDeclarationCollisionSignature.of("1".repeat(64)),
    )

    private fun context(vararg hashes: ExactAdditionContextFileHash): ExactAdditionProofContext =
        ExactAdditionProofContext.of(
            requiredGeneration = MutationSemanticGeneration(7),
            projectModelFingerprint = AdditionProjectModelFingerprint.of("2".repeat(64)),
            classpathFingerprint = AdditionClasspathFingerprint.of("3".repeat(64)),
            contextFileHashes = hashes.toList(),
        )

    private fun withNonZeroRebindingBaseline(query: MutationPostconditionQuery): JsonObject {
        val root = json.parseToJsonElement(
            json.encodeToString(MutationPostconditionQuery.serializer(), query),
        ).jsonObject
        val authority = root.getValue("authority").jsonObject
        val proof = authority.getValue("proof").jsonObject
        val nonZeroBaseline = ExactAdditionRebindingBaseline.complete(
            listOf(
                ExactAdditionRebindingOccurrence.unresolved(
                    filePath = "/workspace/src/main/kotlin/sample/Usage.kt",
                    startOffset = 0,
                    endOffset = 5,
                    reason = AdditionRebindingUnresolvedReason.NOT_FOUND,
                ),
            ),
        )
        val baselineJson = json.parseToJsonElement(
            json.encodeToString(ExactAdditionRebindingBaseline.serializer(), nonZeroBaseline),
        )
        return root.replacing(
            "authority",
            authority.replacing("proof", proof.replacing("rebindingBaseline", baselineJson)),
        )
    }

    private fun JsonObject.replacing(name: String, value: JsonElement): JsonObject =
        JsonObject(toMutableMap().apply { put(name, value) })
}
