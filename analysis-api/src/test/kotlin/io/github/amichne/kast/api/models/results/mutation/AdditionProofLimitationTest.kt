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

class AdditionProofLimitationTest {
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
}
