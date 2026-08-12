package support.architecture.process

import support.architecture.ModuleId

enum class MutationRuntimeProcessId {
    RP01,
    RP02,
    RP03,
    RP04,
    RP05,
    RP06,
    RP07,
    RP08,
    RP09,
    RP10,
    RP11S,
    RP11E,
    RP12,
    RP13,
    RP14,
    RP15,
    RP16,
    RP17,
    RP18,
    RP19,
}

enum class MutationRuntimeEffect {
    NONE,
    PURE,
    READ,
    AUTHORITY,
    SOURCE_WRITE,
    DERIVED_WRITE,
    OBSERVATION,
}

data class MutationRuntimeProcessPolicy(
    val id: MutationRuntimeProcessId,
    val name: String,
    val dependsOn: Set<MutationRuntimeProcessId>,
    val owners: Set<ModuleId>,
    val effects: Set<MutationRuntimeEffect>,
    val cost: String,
)

object KastMutationRuntimeProcesses {
    val all: List<MutationRuntimeProcessPolicy> = listOf(
        process(MutationRuntimeProcessId.RP01, "Parse mutation intent", owners(ModuleId.PROTOCOL_REGISTRY, ModuleId.CHANGE_CONTRACT), effects(MutationRuntimeEffect.PURE), "METADATA"),
        process(MutationRuntimeProcessId.RP02, "Acquire planning read lease", owners(ModuleId.WORKSPACE_CONTRACT, ModuleId.WORKSPACE_SERVICE), effects(MutationRuntimeEffect.READ), "SEMANTIC_ADMISSION", MutationRuntimeProcessId.RP01),
        process(MutationRuntimeProcessId.RP03, "Resolve exact target and affected semantic scope", owners(ModuleId.CHANGE_PLAN_INTELLIJ), effects(MutationRuntimeEffect.READ), "SCOPED_SEMANTIC_READ / BOUNDED_RELATION_SEARCH", MutationRuntimeProcessId.RP02),
        process(MutationRuntimeProcessId.RP04, "Capture stable preconditions and before-image identities", owners(ModuleId.CHANGE_PLAN_SERVICE), effects(MutationRuntimeEffect.READ), "BOUNDED_FILE_OBSERVATION", MutationRuntimeProcessId.RP03),
        process(MutationRuntimeProcessId.RP05, "Construct and persist stable plan", owners(ModuleId.CHANGE_PLAN_SERVICE, ModuleId.CHANGE_JOURNAL_CONTRACT), effects(MutationRuntimeEffect.DERIVED_WRITE), "SMALL_DURABLE_WRITE", MutationRuntimeProcessId.RP04),
        process(MutationRuntimeProcessId.RP06, "Await explicit approval", owners(ModuleId.RUNTIME_SERVER), effects(MutationRuntimeEffect.NONE), "WAIT", MutationRuntimeProcessId.RP05),
        process(MutationRuntimeProcessId.RP07, "Acquire one logical Kast mutation lease", owners(ModuleId.WORKSPACE_MUTATION_SERVICE), effects(MutationRuntimeEffect.AUTHORITY), "CONCURRENCY_ADMISSION", MutationRuntimeProcessId.RP06),
        process(MutationRuntimeProcessId.RP08, "Revalidate plan and exact identity", owners(ModuleId.CHANGE_APPLY_SERVICE, ModuleId.CHANGE_PLAN_INTELLIJ), effects(MutationRuntimeEffect.READ), "SCOPED_SEMANTIC_READ", MutationRuntimeProcessId.RP07),
        process(MutationRuntimeProcessId.RP09, "Prepare durable recovery evidence", owners(ModuleId.CHANGE_RECOVERY_SERVICE, ModuleId.CHANGE_RECOVERY_FILESYSTEM), effects(MutationRuntimeEffect.DERIVED_WRITE), "BOUNDED_FILE_COPY", MutationRuntimeProcessId.RP08),
        process(MutationRuntimeProcessId.RP10, "Prepare ephemeral apply context", owners(ModuleId.CHANGE_APPLY_INTELLIJ, ModuleId.CHANGE_APPLY_FILESYSTEM), effects(MutationRuntimeEffect.READ), "SMALL_PREPARATION", MutationRuntimeProcessId.RP09),
        process(MutationRuntimeProcessId.RP11S, "Apply semantic mutation through IntelliJ", owners(ModuleId.CHANGE_APPLY_INTELLIJ), effects(MutationRuntimeEffect.SOURCE_WRITE), "SHORT_EDT_WRITE_COMMAND", MutationRuntimeProcessId.RP10),
        process(MutationRuntimeProcessId.RP11E, "Apply external file mutation", owners(ModuleId.CHANGE_APPLY_FILESYSTEM), effects(MutationRuntimeEffect.SOURCE_WRITE), "BOUNDED_DESCRIPTOR_RELATIVE_WRITE", MutationRuntimeProcessId.RP10),
        process(MutationRuntimeProcessId.RP12, "Persist affected documents and capture after-images", owners(ModuleId.CHANGE_APPLY_SERVICE), effects(MutationRuntimeEffect.SOURCE_WRITE, MutationRuntimeEffect.OBSERVATION), "BOUNDED_BY_DECLARED_WRITE_SET", MutationRuntimeProcessId.RP11S, MutationRuntimeProcessId.RP11E),
        process(MutationRuntimeProcessId.RP13, "Prove write-set closure", owners(ModuleId.CHANGE_APPLY_SERVICE), effects(MutationRuntimeEffect.READ), "BOUNDED_DIFF", MutationRuntimeProcessId.RP12),
        process(MutationRuntimeProcessId.RP14, "Request targeted workspace transition", owners(ModuleId.WORKSPACE_SERVICE), effects(MutationRuntimeEffect.DERIVED_WRITE), "INCREMENTAL_RECONCILIATION", MutationRuntimeProcessId.RP13),
        process(MutationRuntimeProcessId.RP15, "Publish resulting semantic generation", owners(ModuleId.WORKSPACE_SERVICE, ModuleId.EVIDENCE_SQLITE), effects(MutationRuntimeEffect.DERIVED_WRITE), "INCREMENTAL_INDEX / ATOMIC_PUBLICATION", MutationRuntimeProcessId.RP14),
        process(MutationRuntimeProcessId.RP16, "Evaluate diagnostics and semantic postconditions", owners(ModuleId.CHANGE_VERIFY_INTELLIJ), effects(MutationRuntimeEffect.READ), "SCOPED_SMART_READ", MutationRuntimeProcessId.RP15),
        process(MutationRuntimeProcessId.RP17, "Reconcile expected and observed result", owners(ModuleId.CHANGE_VERIFY_SERVICE), effects(MutationRuntimeEffect.PURE), "BOUNDED_COMPARISON", MutationRuntimeProcessId.RP16),
        process(MutationRuntimeProcessId.RP18, "Issue verified receipt and release recovery", owners(ModuleId.CHANGE_VERIFY_SERVICE, ModuleId.CHANGE_JOURNAL_CONTRACT), effects(MutationRuntimeEffect.DERIVED_WRITE), "SMALL_DURABLE_WRITE", MutationRuntimeProcessId.RP17),
        process(MutationRuntimeProcessId.RP19, "Rollback or retain recovery after failure", owners(ModuleId.CHANGE_RECOVERY_SERVICE), effects(MutationRuntimeEffect.SOURCE_WRITE, MutationRuntimeEffect.DERIVED_WRITE), "FAILURE_DEPENDENT", MutationRuntimeProcessId.RP09, MutationRuntimeProcessId.RP11S, MutationRuntimeProcessId.RP11E, MutationRuntimeProcessId.RP12, MutationRuntimeProcessId.RP13, MutationRuntimeProcessId.RP14, MutationRuntimeProcessId.RP15, MutationRuntimeProcessId.RP16, MutationRuntimeProcessId.RP17),
    )

    private fun process(
        id: MutationRuntimeProcessId,
        name: String,
        owners: Set<ModuleId>,
        effects: Set<MutationRuntimeEffect>,
        cost: String,
        vararg dependencies: MutationRuntimeProcessId,
    ): MutationRuntimeProcessPolicy = MutationRuntimeProcessPolicy(id, name, dependencies.toSet(), owners, effects, cost)

    private fun owners(vararg ids: ModuleId): Set<ModuleId> = ids.toSet()

    private fun effects(vararg effects: MutationRuntimeEffect): Set<MutationRuntimeEffect> = effects.toSet()
}
