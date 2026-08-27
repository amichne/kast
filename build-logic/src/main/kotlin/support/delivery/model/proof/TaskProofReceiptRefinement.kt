package support.delivery

private val taskProofVersionPattern = Regex("[a-z0-9][a-z0-9.-]+")
private val taskProofReceiptIdPattern = Regex("KVP-[0-9]{3}-COMPLETE")
private val taskProofTaskIdPattern = Regex("KVP-[0-9]{3}")
private val taskProofSha256Pattern = Regex("[0-9a-f]{64}")
private val taskProofObservationNamePattern = Regex("[a-z][A-Za-z0-9]*")

/**
 * Proof transition: raw task-proof contract fields -> `TaskProofReceiptExpectationRefinement`.
 *
 * Establishes one v2 program version, task-bound completion receipt identity, complete dependency
 * digest closure, relevant-input/command/toolchain digests, nonempty complete observations,
 * portable output digests, and closed head policy. Expected malformed data returns finite
 * [TaskProofReceiptFailure]. Raw values remain at Gradle and JSON boundaries.
 */
internal fun refineTaskProofReceiptExpectation(
    programVersion: String,
    receiptId: String,
    taskId: String,
    taskDefinitionDigest: String,
    dependencyReceiptDigests: Map<String, String>,
    relevantInputDigest: String,
    commandDigest: String,
    toolchainDigest: String,
    completeObservations: Map<String, String>,
    outputDigests: Map<String, String>,
    headPolicy: String,
): TaskProofReceiptExpectationRefinement {
    fun rejected(failure: TaskProofReceiptFailure) =
        TaskProofReceiptExpectationRefinement.Rejected(failure)
    if (!taskProofVersionPattern.matches(programVersion)) {
        return rejected(TaskProofReceiptFailure.MALFORMED_PROGRAM_VERSION)
    }
    if (!taskProofReceiptIdPattern.matches(receiptId)) {
        return rejected(TaskProofReceiptFailure.MALFORMED_RECEIPT_ID)
    }
    if (!taskProofTaskIdPattern.matches(taskId)) {
        return rejected(TaskProofReceiptFailure.MALFORMED_TASK_ID)
    }
    if (receiptId != "$taskId-COMPLETE") {
        return rejected(TaskProofReceiptFailure.RECEIPT_TASK_MISMATCH)
    }
    if (listOf(
            taskDefinitionDigest,
            relevantInputDigest,
            commandDigest,
            toolchainDigest,
        ).any { !taskProofSha256Pattern.matches(it) }
    ) return rejected(TaskProofReceiptFailure.MALFORMED_DIGEST)
    if (dependencyReceiptDigests.isEmpty() || dependencyReceiptDigests.any { (id, digest) ->
            !taskProofReceiptIdPattern.matches(id) || !taskProofSha256Pattern.matches(digest)
        }
    ) return rejected(TaskProofReceiptFailure.MALFORMED_DEPENDENCY_RECEIPTS)
    if (completeObservations.isEmpty() || completeObservations.any { (name, value) ->
            !taskProofObservationNamePattern.matches(name) || value.isBlank()
        }
    ) return rejected(TaskProofReceiptFailure.MALFORMED_OBSERVATION)
    if (outputDigests.isEmpty() || outputDigests.any { (path, digest) ->
            !path.isPortableTaskProofPath() || !taskProofSha256Pattern.matches(digest)
        }
    ) return rejected(TaskProofReceiptFailure.MALFORMED_OUTPUT)
    val parsedHeadPolicy = TaskProofHeadPolicy.entries.singleOrNull { it.name == headPolicy }
        ?: return rejected(TaskProofReceiptFailure.MALFORMED_HEAD_POLICY)
    val outcome = TaskProofOutcome.Complete(
        completeObservations.mapKeys { TaskProofObservationName(it.key) }
            .mapValues { TaskProofObservationValue(it.value) },
    )
    return TaskProofReceiptExpectationRefinement.Complete(
        TaskProofReceiptExpectation.admitted(
            TaskProofProgramVersion(programVersion),
            ReceiptId(receiptId),
            TaskId(taskId),
            TaskDefinitionDigest(taskDefinitionDigest),
            dependencyReceiptDigests.mapKeys { ReceiptId(it.key) }
                .mapValues { TaskProofDependencyDigest(it.value) },
            RelevantInputDigest(relevantInputDigest),
            TaskProofCommandDigest(commandDigest),
            ToolchainDigest(toolchainDigest),
            outcome,
            outputDigests.mapKeys { TaskProofOutputPath(it.key) }
                .mapValues { TaskProofOutputDigest(it.value) },
            parsedHeadPolicy,
        ),
    )
}

internal fun String.isPortableTaskProofPath(): Boolean =
    isNotBlank() && !startsWith('/') && '\\' !in this &&
        split('/').none { it.isEmpty() || it == "." || it == ".." }
