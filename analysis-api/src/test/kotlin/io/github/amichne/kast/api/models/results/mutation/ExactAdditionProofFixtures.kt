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

internal fun addFileProof(
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

internal fun addDeclarationProof(
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

internal fun owner() = io.github.amichne.kast.api.contract.result.AdditionSourceOwner.of(
    sourceRoot = AdditionSourceRoot.parse("/workspace/src/main/kotlin"),
    ideaModuleName = AdditionIdeaModuleName.of("root.main"),
    gradleBuildRoot = AdditionGradleBuildRoot.parse("/workspace"),
    gradleProjectPath = AdditionGradleProjectPath.parse(":"),
    sourceSetName = AdditionGradleSourceSetName.of("main"),
)

internal fun packageIdentity(): AdditionKotlinPackage = AdditionKotlinPackage.Named.of("sample")

internal fun declaration(
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

internal fun addFileContext(): ExactAdditionProofContext = ExactAdditionProofContext.of(
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

internal fun addDeclarationContext(): ExactAdditionProofContext = ExactAdditionProofContext.of(
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

internal fun outboundEvidence(): ExactAdditionOutboundEvidence =
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

internal fun zeroRebindingBaseline(): ExactAdditionRebindingBaseline =
    ExactAdditionRebindingBaseline.complete(emptyList())

internal fun nonZeroRebindingBaseline(): ExactAdditionRebindingBaseline =
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

internal fun sourceTarget(): AdditionResolvedTarget = AdditionResolvedTarget.Source.of(
    SymbolIdentity(
        fqName = "sample.Helper",
        kind = SymbolKind.CLASS,
        declarationFile = NormalizedPath.parse("/workspace/src/main/kotlin/sample/Helper.kt"),
        declarationStartOffset = NonNegativeInt(0),
    ),
)

internal fun JsonObject.replacing(name: String, value: JsonElement): JsonObject =
    JsonObject(toMutableMap().apply { put(name, value) })
