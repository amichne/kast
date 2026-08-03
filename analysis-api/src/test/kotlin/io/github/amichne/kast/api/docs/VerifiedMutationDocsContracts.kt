package io.github.amichne.kast.api.docs

internal data class VerifiedMutationDocsContract(
    val operationId: String,
    val method: String,
    val capability: String,
    val requestSchema: String,
    val responseSchema: String,
)

internal val verifiedMutationDocsContracts = listOf(
    VerifiedMutationDocsContract(
        operationId = "planReplacement",
        method = "raw/plan-replacement",
        capability = "PLAN_REPLACEMENT",
        requestSchema = "ReplacementPlanQuery",
        responseSchema = "ReplacementPlanResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "planAddFile",
        method = "raw/plan-add-file",
        capability = "PLAN_ADD_FILE",
        requestSchema = "AddFilePlanQuery",
        responseSchema = "AddFilePlanResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "planAddDeclaration",
        method = "raw/plan-add-declaration",
        capability = "PLAN_ADD_DECLARATION",
        requestSchema = "AddDeclarationPlanQuery",
        responseSchema = "AddDeclarationPlanResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "exactFileImageCas",
        method = "raw/exact-file-image-cas",
        capability = "EXACT_FILE_IMAGE_CAS",
        requestSchema = "ExactFileImageQuery",
        responseSchema = "ExactFileImageResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "exactFileObservation",
        method = "raw/exact-file-observation",
        capability = "EXACT_FILE_OBSERVATION",
        requestSchema = "RawExactFileObservationQuery",
        responseSchema = "RawExactFileObservationResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "inspectMutationScratch",
        method = "raw/inspect-mutation-scratch",
        capability = "MUTATION_SCRATCH_RECOVERY",
        requestSchema = "MutationScratchInspectQuery",
        responseSchema = "MutationScratchInspectResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "recoverMutationScratch",
        method = "raw/recover-mutation-scratch",
        capability = "MUTATION_SCRATCH_RECOVERY",
        requestSchema = "MutationScratchRecoveryQuery",
        responseSchema = "MutationScratchRecoveryResult",
    ),
    VerifiedMutationDocsContract(
        operationId = "verifyMutationPostcondition",
        method = "raw/verify-mutation-postcondition",
        capability = "VERIFY_MUTATION_POSTCONDITION",
        requestSchema = "MutationPostconditionQuery",
        responseSchema = "MutationPostconditionResult",
    ),
)

internal data class ClosedMutationSchema(
    val discriminator: String,
    val variants: List<String>,
)

internal val closedMutationSchemas = mapOf(
    "ContainingSymbolEvidence" to ClosedMutationSchema("type", listOf("KNOWN", "TOP_LEVEL", "UNAVAILABLE")),
    "ReplacementDeclarationSignature" to ClosedMutationSchema("type", listOf("function", "property")),
    "ReplacementOutboundEvidence" to ClosedMutationSchema("type", listOf("complete", "limited")),
    "ReplacementOutboundTarget" to ClosedMutationSchema("type", listOf("source", "external")),
    "AdditionKotlinPackage" to ClosedMutationSchema("type", listOf("ROOT", "NAMED")),
    "AdditionResolvedTarget" to ClosedMutationSchema("type", listOf("SOURCE", "EXTERNAL")),
    "AdditionRebindingCurrentTarget" to ClosedMutationSchema("type", listOf("RESOLVED", "UNRESOLVED")),
    "RawExactFileObservationResult" to ClosedMutationSchema("type", listOf("ABSENT", "PRESENT")),
    "MutationScratchRecoveryPreimage" to ClosedMutationSchema("state", listOf("ABSENT", "PRESENT")),
    "MutationPostconditionAuthority" to ClosedMutationSchema(
        "type",
        listOf("RENAME", "REPLACEMENT", "ADD_FILE", "ADD_DECLARATION"),
    ),
    "MutationPostconditionEvidence" to ClosedMutationSchema(
        "type",
        listOf("RENAME", "REPLACEMENT", "ADD_FILE", "ADD_DECLARATION"),
    ),
)
