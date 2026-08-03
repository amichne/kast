package io.github.amichne.kast.server

import io.github.amichne.kast.api.contract.ExactByteImage
import io.github.amichne.kast.api.contract.ExactFileImage
import io.github.amichne.kast.api.contract.ExactFileImagePath
import io.github.amichne.kast.api.contract.FileHash
import io.github.amichne.kast.api.contract.Location
import io.github.amichne.kast.api.contract.MutationScratchSet
import io.github.amichne.kast.api.contract.NonNegativeInt
import io.github.amichne.kast.api.contract.NormalizedPath
import io.github.amichne.kast.api.contract.SymbolIdentity
import io.github.amichne.kast.api.contract.SymbolKind
import io.github.amichne.kast.api.contract.TextEdit
import io.github.amichne.kast.api.contract.query.AddDeclarationPlanQuery
import io.github.amichne.kast.api.contract.query.AddFilePlanQuery
import io.github.amichne.kast.api.contract.query.ExactFileImageQuery
import io.github.amichne.kast.api.contract.query.MutationPostconditionAuthority
import io.github.amichne.kast.api.contract.query.MutationPostconditionQuery
import io.github.amichne.kast.api.contract.query.MutationScratchDirection
import io.github.amichne.kast.api.contract.query.MutationScratchInspectQuery
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryAction
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryPreimage
import io.github.amichne.kast.api.contract.query.MutationScratchRecoveryQuery
import io.github.amichne.kast.api.contract.query.RawExactFileObservationQuery
import io.github.amichne.kast.api.contract.query.ReplacementPlanQuery
import io.github.amichne.kast.api.contract.result.AddDeclarationPlanResult
import io.github.amichne.kast.api.contract.result.AddFilePlanResult
import io.github.amichne.kast.api.contract.result.AdditionClasspathFingerprint
import io.github.amichne.kast.api.contract.result.AdditionDeclarationCollisionSignature
import io.github.amichne.kast.api.contract.result.AdditionGradleBuildRoot
import io.github.amichne.kast.api.contract.result.AdditionGradleProjectPath
import io.github.amichne.kast.api.contract.result.AdditionGradleSourceSetName
import io.github.amichne.kast.api.contract.result.AdditionIdeaModuleName
import io.github.amichne.kast.api.contract.result.AdditionKotlinPackage
import io.github.amichne.kast.api.contract.result.AdditionNewlinePolicy
import io.github.amichne.kast.api.contract.result.AdditionPostimageSha256
import io.github.amichne.kast.api.contract.result.AdditionProjectModelFingerprint
import io.github.amichne.kast.api.contract.result.AdditionSourceOwner
import io.github.amichne.kast.api.contract.result.AdditionSourceRoot
import io.github.amichne.kast.api.contract.result.AdditionTargetPath
import io.github.amichne.kast.api.contract.result.AdditionTargetPreimageSha256
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclaration
import io.github.amichne.kast.api.contract.result.AdditionTopLevelDeclarationKind
import io.github.amichne.kast.api.contract.result.CompilerFileBottomInsertion
import io.github.amichne.kast.api.contract.result.ExactAddDeclarationProof
import io.github.amichne.kast.api.contract.result.ExactAddFileProof
import io.github.amichne.kast.api.contract.result.ExactAdditionCollisionEvidence
import io.github.amichne.kast.api.contract.result.ExactAdditionContextFileHash
import io.github.amichne.kast.api.contract.result.ExactAdditionOutboundEvidence
import io.github.amichne.kast.api.contract.result.ExactAdditionProofContext
import io.github.amichne.kast.api.contract.result.ExactAdditionRebindingBaseline
import io.github.amichne.kast.api.contract.result.ExactReplacementProof
import io.github.amichne.kast.api.contract.result.MutationPostconditionEvidence
import io.github.amichne.kast.api.contract.result.MutationPostconditionOperation
import io.github.amichne.kast.api.contract.result.MutationPostconditionResult
import io.github.amichne.kast.api.contract.result.MutationSemanticGeneration
import io.github.amichne.kast.api.contract.result.RawExactFileObservationResult
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSha256
import io.github.amichne.kast.api.contract.result.ReplacementDeclarationSlice
import io.github.amichne.kast.api.contract.result.ReplacementFunctionSignature
import io.github.amichne.kast.api.contract.result.ReplacementModality
import io.github.amichne.kast.api.contract.result.ReplacementOutboundEvidence
import io.github.amichne.kast.api.contract.result.ReplacementPlanResult
import io.github.amichne.kast.api.contract.result.ReplacementVisibility
import io.github.amichne.kast.api.contract.result.VerifiedMutationPostimage
import io.github.amichne.kast.api.protocol.JsonRpcRequest
import io.github.amichne.kast.api.validation.FileHashing
import io.github.amichne.kast.testing.FakeAnalysisBackend
import java.nio.file.Path
import kotlinx.serialization.json.Json

internal data class DocExampleGeneratorMutationFixture(
    val backend: DocExampleGeneratorMutationBackend,
    val operations: List<Pair<String, JsonRpcRequest>>,
)

internal fun buildDocExampleGeneratorMutationFixture(
    json: Json,
    delegate: FakeAnalysisBackend,
    workspaceRoot: Path,
    sampleFile: String,
    sampleContent: String,
): DocExampleGeneratorMutationFixture {
    val sourceRoot = workspaceRoot.resolve("src").toAbsolutePath().normalize()
    val owner = additionOwner(workspaceRoot, sourceRoot)
    val packageIdentity = AdditionKotlinPackage.Named.of("sample")
    val replacement = replacementResult(sampleFile, sampleContent)
    val addFile = addFileResult(sourceRoot, owner, packageIdentity)
    val addDeclaration = addDeclarationResult(sampleFile, sampleContent, owner, packageIdentity)
    val observation = RawExactFileObservationResult.Present(
        filePath = io.github.amichne.kast.api.contract.RawExactFileObservationPath.parse(SAMPLE_RELATIVE_PATH),
        image = ExactByteImage.of(sampleContent.toByteArray()),
    )
    val postcondition = MutationPostconditionResult.verified(
        operation = MutationPostconditionOperation.ADD_FILE,
        currentGeneration = MutationSemanticGeneration(2),
        postimages = listOf(VerifiedMutationPostimage(ExactFileImagePath(addFile.proof.targetPath.value), addFile.postimage.sha256)),
        evidence = MutationPostconditionEvidence.AddFile(
            owner = addFile.proof.owner,
            packageIdentity = addFile.proof.packageIdentity,
            declarations = addFile.proof.declarations,
            outboundEvidence = addFile.proof.outboundEvidence,
        ),
    )
    val responses = DocExampleGeneratorMutationResponses(
        replacement = replacement,
        addFile = addFile,
        addDeclaration = addDeclaration,
        postcondition = postcondition,
        observation = observation,
    )

    val attemptId = MUTATION_ATTEMPT_ID
    val scratch = mutationScratchSet(Path.of(sampleFile), attemptId)
    val casPostimage = ExactByteImage.of(("// exact image example\n" + sampleContent).toByteArray())
    val operations = listOf(
        "planReplacement" to request(
            "raw/plan-replacement",
            json.encodeToJsonElement(
                ReplacementPlanQuery.serializer(),
                ReplacementPlanQuery(replacement.proof.target, replacement.edit.newText),
            ),
        ),
        "planAddFile" to request(
            "raw/plan-add-file",
            json.encodeToJsonElement(
                AddFilePlanQuery.serializer(),
                AddFilePlanQuery(addFile.proof.targetPath, addFile.proposedContent),
            ),
        ),
        "planAddDeclaration" to request(
            "raw/plan-add-declaration",
            json.encodeToJsonElement(
                AddDeclarationPlanQuery.serializer(),
                AddDeclarationPlanQuery(
                    addDeclaration.proof.targetPath,
                    addDeclaration.proof.targetPreimageSha256,
                    addDeclaration.proposedDeclaration,
                ),
            ),
        ),
        "verifyMutationPostcondition" to request(
            "raw/verify-mutation-postcondition",
            json.encodeToJsonElement(
                MutationPostconditionQuery.serializer(),
                MutationPostconditionQuery(
                    MutationPostconditionAuthority.AddFile(addFile.proof, addFile.postimage),
                ),
            ),
        ),
        "exactFileObservation" to request(
            "raw/exact-file-observation",
            json.encodeToJsonElement(
                RawExactFileObservationQuery.serializer(),
                RawExactFileObservationQuery(SAMPLE_RELATIVE_PATH),
            ),
        ),
        "exactFileImageCas" to request(
            "raw/exact-file-image-cas",
            json.encodeToJsonElement(
                ExactFileImageQuery.serializer(),
                ExactFileImageQuery(
                    filePath = ExactFileImagePath(sampleFile),
                    expectedCurrentSha256 = observation.image.sha256,
                    contentBase64 = casPostimage.contentBase64,
                    expectedResultSha256 = casPostimage.sha256,
                ),
            ),
        ),
        "inspectMutationScratch" to request(
            "raw/inspect-mutation-scratch",
            json.encodeToJsonElement(
                MutationScratchInspectQuery.serializer(),
                MutationScratchInspectQuery(attemptId, listOf("src"), listOf(scratch)),
            ),
        ),
        "recoverMutationScratch" to request(
            "raw/recover-mutation-scratch",
            json.encodeToJsonElement(
                MutationScratchRecoveryQuery.serializer(),
                MutationScratchRecoveryQuery(
                    mutationAttemptId = attemptId,
                    action = MutationScratchRecoveryAction.RESTORE_PREIMAGE,
                    scratchDirection = MutationScratchDirection.RESTORE_PREIMAGE,
                    targetFilePath = sampleFile,
                    preimage = MutationScratchRecoveryPreimage.Present(observation.image),
                    postimage = casPostimage,
                    scratch = scratch,
                ),
            ),
        ),
    )
    return DocExampleGeneratorMutationFixture(
        backend = DocExampleGeneratorMutationBackend(delegate, responses),
        operations = operations,
    )
}

internal fun insertDocMutationOperations(
    base: List<Pair<String, JsonRpcRequest>>,
    mutation: List<Pair<String, JsonRpcRequest>>,
): List<Pair<String, JsonRpcRequest>> {
    require(base.lastOrNull()?.first == "applyEdits")
    return base.dropLast(1) + mutation + base.last()
}

private fun replacementResult(sampleFile: String, sampleContent: String): ReplacementPlanResult {
    val sourceStart = sampleContent.indexOf("fun greet")
    val sourceEnd = sampleContent.indexOf('\n', sourceStart)
    require(sourceStart >= 0 && sourceEnd > sourceStart)
    val proposed = "fun greet() = \"hello\""
    val target = SymbolIdentity(
        fqName = "sample.greet",
        kind = SymbolKind.FUNCTION,
        declarationFile = NormalizedPath.parse(sampleFile),
        declarationStartOffset = NonNegativeInt(sampleContent.indexOf("greet", sourceStart)),
    )
    val signature = replacementSignature()
    val proof = ExactReplacementProof.of(
        target = target,
        requiredGeneration = MutationSemanticGeneration(1),
        sourceRange = Location(sampleFile, sourceStart, sourceEnd, 3, 1, sampleContent.substring(sourceStart, sourceEnd)),
        fileHashes = listOf(FileHash(sampleFile, FileHashing.sha256(sampleContent))),
        oldSignature = signature,
        proposedSignature = signature,
        proposedDeclarationHash = ReplacementDeclarationSha256(FileHashing.sha256(proposed)),
        proposedDeclarationLength = proposed.length,
        declarationSlice = ReplacementDeclarationSlice(NonNegativeInt(0), NonNegativeInt(proposed.length)),
        evidence = ReplacementOutboundEvidence.Complete.of(0),
        outboundReferences = emptyList(),
    )
    val edit = TextEdit(sampleFile, sourceStart, sourceEnd, proposed)
    val image = ExactFileImage.of(
        sampleFile,
        sampleContent.toByteArray(),
        sampleContent.replaceRange(sourceStart, sourceEnd, proposed).toByteArray(),
    )
    return ReplacementPlanResult.of(edit, proof, listOf(image))
}

private fun replacementSignature(): ReplacementFunctionSignature = ReplacementFunctionSignature.of(
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

private fun addFileResult(
    sourceRoot: Path,
    owner: AdditionSourceOwner,
    packageIdentity: AdditionKotlinPackage,
): AddFilePlanResult {
    val content = "package sample\n\nclass Generated\n"
    val declarationText = "class Generated"
    val declarationStart = content.indexOf(declarationText)
    val proof = ExactAddFileProof.of(
        targetPath = AdditionTargetPath.parse(sourceRoot.resolve("Generated.kt").toString()),
        owner = owner,
        packageIdentity = packageIdentity,
        declarations = listOf(additionDeclaration(packageIdentity, "Generated", declarationStart, declarationText)),
        context = additionContext(),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
        rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
        postimageSha256 = AdditionPostimageSha256.of(FileHashing.sha256(content.toByteArray())),
    )
    return AddFilePlanResult.of(content, proof)
}

private fun addDeclarationResult(
    sampleFile: String,
    sampleContent: String,
    owner: AdditionSourceOwner,
    packageIdentity: AdditionKotlinPackage,
): AddDeclarationPlanResult {
    val proposed = "class AddedDeclaration"
    val preimage = sampleContent.toByteArray()
    val postimage = preimage + ("\n$proposed\n").toByteArray()
    val image = ExactFileImage.of(sampleFile, preimage, postimage)
    val preimageSha256 = FileHashing.sha256(preimage)
    val proof = ExactAddDeclarationProof.of(
        targetPath = AdditionTargetPath.parse(sampleFile),
        targetPreimageSha256 = AdditionTargetPreimageSha256.of(preimageSha256),
        owner = owner,
        packageIdentity = packageIdentity,
        declaration = additionDeclaration(packageIdentity, "AddedDeclaration", 0, proposed),
        insertion = CompilerFileBottomInsertion.at(sampleContent.length),
        newlinePolicy = AdditionNewlinePolicy.PRESERVE_EXISTING_APPEND_BLANK_LINE_FINAL_LF,
        context = additionContext(ExactAdditionContextFileHash.of(sampleFile, preimageSha256)),
        collisionEvidence = ExactAdditionCollisionEvidence.complete(1),
        outboundEvidence = ExactAdditionOutboundEvidence.complete(emptyList()),
        rebindingBaseline = ExactAdditionRebindingBaseline.complete(emptyList()),
        postimageSha256 = AdditionPostimageSha256.of(image.postimage.sha256.value),
    )
    return AddDeclarationPlanResult.of(proposed, image, proof)
}

private fun additionOwner(workspaceRoot: Path, sourceRoot: Path): AdditionSourceOwner = AdditionSourceOwner.of(
    sourceRoot = AdditionSourceRoot.parse(sourceRoot.toString()),
    ideaModuleName = AdditionIdeaModuleName.of("fake-module"),
    gradleBuildRoot = AdditionGradleBuildRoot.parse(workspaceRoot.toAbsolutePath().normalize().toString()),
    gradleProjectPath = AdditionGradleProjectPath.parse(":"),
    sourceSetName = AdditionGradleSourceSetName.of("main"),
)

private fun additionDeclaration(
    packageIdentity: AdditionKotlinPackage,
    name: String,
    start: Int,
    declarationText: String,
): AdditionTopLevelDeclaration = AdditionTopLevelDeclaration.of(
    packageIdentity = packageIdentity,
    name = name,
    kind = AdditionTopLevelDeclarationKind.CLASS,
    relativeStartOffset = start,
    relativeEndOffset = start + declarationText.length,
    collisionSignature = AdditionDeclarationCollisionSignature.of(FileHashing.sha256(declarationText)),
)

private fun additionContext(vararg hashes: ExactAdditionContextFileHash): ExactAdditionProofContext =
    ExactAdditionProofContext.of(
        requiredGeneration = MutationSemanticGeneration(1),
        projectModelFingerprint = AdditionProjectModelFingerprint.of(FileHashing.sha256("fake-project-model")),
        classpathFingerprint = AdditionClasspathFingerprint.of(FileHashing.sha256("fake-classpath")),
        contextFileHashes = hashes.toList(),
    )

private fun mutationScratchSet(target: Path, attemptId: String): MutationScratchSet {
    val parent = requireNotNull(target.parent)
    return MutationScratchSet(
        targetFilePath = target.toString(),
        quarantinePath = parent.resolve(".kast-quarantine-$attemptId-0").toString(),
        preparedPath = parent.resolve(".kast-prepared-$attemptId-0.tmp").toString(),
        preparedCleanupPath = parent.resolve(".kast-cleanup-$attemptId-0-prepared").toString(),
        quarantineCleanupPath = parent.resolve(".kast-cleanup-$attemptId-0-quarantine").toString(),
    )
}

private const val SAMPLE_RELATIVE_PATH = "src/Sample.kt"
private const val MUTATION_ATTEMPT_ID = "00000000-0000-4000-8000-000000000004"
