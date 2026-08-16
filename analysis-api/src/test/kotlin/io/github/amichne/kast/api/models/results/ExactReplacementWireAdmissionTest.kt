package io.github.amichne.kast.api

import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.ReplacementBodySha256
import io.github.amichne.kast.api.contract.result.ReplacementCompilerContext
import io.github.amichne.kast.api.contract.result.ReplacementCompilerModelGeneration
import io.github.amichne.kast.api.contract.result.ReplacementContractAdmission
import io.github.amichne.kast.api.contract.result.ReplacementContractFailure
import io.github.amichne.kast.api.contract.result.ReplacementContractWireException
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementSubmittedBodySlice
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.validation.FileHashing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ExactReplacementWireAdmissionTest {
    private val json = Json { explicitNulls = false }

    @Test
    fun `decoder requires every full request and body authority field`() {
        val encoded = json.encodeToJsonElement(ExactReplacementProof.serializer(), validProof()).jsonObject

        listOf(
            "compilerContext",
            "proposedDeclarationHash",
            "proposedDeclarationLength",
            "proposedBodyHash",
            "proposedBodyLength",
            "declarationSlice",
            "proposedBodySlice",
        ).forEach { requiredField ->
            val missing = JsonObject(encoded.filterKeys { key -> key != requiredField })
            assertThrows(Exception::class.java, {
                json.decodeFromJsonElement(ExactReplacementProof.serializer(), missing)
            }, requiredField)
        }
    }

    @Test
    fun `decoder maps malformed body digest to finite wire failure`() {
        val encoded = json.encodeToJsonElement(ExactReplacementProof.serializer(), validProof()).jsonObject
        val malformed = JsonObject(encoded + ("proposedBodyHash" to JsonPrimitive("not-a-digest")))

        val failure = assertThrows(ReplacementContractWireException::class.java) {
            json.decodeFromJsonElement(ExactReplacementProof.serializer(), malformed)
        }

        assertEquals(ReplacementContractFailure.BODY_SHA256_INVALID, failure.failure)
    }

    @Test
    fun `decoder rejects property identity from function-only replacement contract`() {
        val encoded = json.encodeToJsonElement(ExactReplacementProof.serializer(), validProof()).jsonObject
        val target = encoded.getValue("target").jsonObject
        val malformed = JsonObject(
            encoded + ("target" to JsonObject(target + ("kind" to JsonPrimitive("PROPERTY")))),
        )

        val failure = assertThrows(ReplacementContractWireException::class.java) {
            json.decodeFromJsonElement(ExactReplacementProof.serializer(), malformed)
        }

        assertEquals(ReplacementContractFailure.TARGET_NOT_FUNCTION, failure.failure)
    }

    @Test
    fun `replacement result decoder requires images and rejects incoherent replay`() {
        val valid = validPlan()
        val encoded = json.encodeToJsonElement(ReplacementPlanResult.serializer(), valid).jsonObject
        val missingImages = JsonObject(encoded.filterKeys { key -> key != "fileImages" })
        assertThrows(Exception::class.java) {
            json.decodeFromJsonElement(ReplacementPlanResult.serializer(), missingImages)
        }

        val image = valid.fileImages.single()
        val unrelated = ExactFileImage.of(
            filePath = image.filePath.value,
            preimageBytes = image.preimage.copyBytes(),
            postimageBytes = image.postimage.copyBytes() + "// unrelated".toByteArray(),
        )
        val malformed = JsonObject(
            encoded + (
                "fileImages" to JsonArray(
                    listOf(json.encodeToJsonElement(ExactFileImage.serializer(), unrelated)),
                )
            ),
        )
        val failure = assertThrows(ReplacementContractWireException::class.java) {
            json.decodeFromJsonElement(ReplacementPlanResult.serializer(), malformed)
        }
        assertEquals(ReplacementContractFailure.POSTIMAGE_REPLAY_INVALID, failure.failure)
    }

    private fun validPlan(): ReplacementPlanResult {
        val proposed = PROPOSED_BODY
        val preimage = "head" + "x".repeat(36) + "tail"
        val image = ExactFileImage.of(
            filePath = SOURCE_PATH,
            preimageBytes = preimage.toByteArray(),
            postimageBytes = preimage.replaceRange(4, 40, proposed).toByteArray(),
        )
        return admitted(
            ReplacementPlanResult.admit(
                edit = TextEdit(SOURCE_PATH, 4, 40, proposed),
                proof = validProof(proposed, image.preimage.sha256.value),
                fileImages = listOf(image),
            ),
        )
    }

    private fun validProof(
        proposed: String = PROPOSED_BODY,
        fileHash: String = "1".repeat(64),
    ): ExactReplacementProof {
        val signature = ReplacementFunctionSignature.of(
            name = "greet",
            receiverType = null,
            contextReceiverTypes = emptyList(),
            typeParameters = emptyList(),
            valueParameters = emptyList(),
            returnType = "kotlin.String",
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
        return admitted(
            ExactReplacementProof.admit(
                target = SymbolIdentity(
                    fqName = "sample.greet",
                    kind = SymbolKind.FUNCTION,
                    declarationFile = NormalizedPath.parse(SOURCE_PATH),
                    declarationStartOffset = NonNegativeInt(0),
                ),
                requiredGeneration = MutationSemanticGeneration(7),
                sourceRange = Location(SOURCE_PATH, 4, 40, 1, 1, "fun greet"),
                fileHashes = listOf(FileHash(SOURCE_PATH, fileHash)),
                compilerContext = ReplacementCompilerContext.of(
                    emptyMap(),
                    admitted(ReplacementCompilerModelGeneration.parse(1)),
                ),
                oldSignature = signature,
                proposedSignature = signature,
                proposedDeclarationHash = admitted(
                    ReplacementDeclarationSha256.parse(FileHashing.sha256(proposed)),
                ),
                proposedDeclarationLength = proposed.length,
                proposedBodyHash = admitted(
                    ReplacementBodySha256.parse(FileHashing.sha256(proposed)),
                ),
                proposedBodyLength = proposed.length,
                declarationSlice = admitted(
                    ReplacementDeclarationSlice.of(NonNegativeInt(0), NonNegativeInt(proposed.length)),
                ),
                proposedBodySlice = admitted(
                    ReplacementSubmittedBodySlice.of(NonNegativeInt(0), NonNegativeInt(proposed.length)),
                ),
                evidence = ReplacementOutboundEvidence.Complete.of(0),
                outboundReferences = emptyList(),
            ),
        )
    }

    private fun <Value> admitted(admission: ReplacementContractAdmission<Value>): Value =
        (admission as ReplacementContractAdmission.Admitted).value

    private companion object {
        const val SOURCE_PATH = "/workspace/src/Sample.kt"
        const val PROPOSED_BODY = "fun greet(value: String): String = value"
    }
}
