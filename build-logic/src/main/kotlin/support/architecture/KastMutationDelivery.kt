package support.architecture

internal object KastMutationDelivery {
    // @formatter:off
    val all: List<MutationDeliveryTaskPolicy> = listOf(
        task(MutationDeliveryTaskId.F01, MutationDeliveryPhase.FOUNDATION, "Freeze mutation lifecycle and canonical contracts", module(ModuleId.CHANGE_CONTRACT)),
        task(MutationDeliveryTaskId.F02, MutationDeliveryPhase.FOUNDATION, "Create Gradle module-role convention plugins", MutationDeliveryOwner.BuildLogic, MutationDeliveryTaskId.F01),
        task(MutationDeliveryTaskId.F03, MutationDeliveryPhase.FOUNDATION, "Enforce dependency graph and forbidden effects", MutationDeliveryOwner.BuildLogic, MutationDeliveryTaskId.F02),
        task(MutationDeliveryTaskId.F04, MutationDeliveryPhase.FOUNDATION, "Reduce AnalysisBackend to compatibility transport", module(ModuleId.RUNTIME_SERVER), MutationDeliveryTaskId.F01, MutationDeliveryTaskId.F03),
        task(MutationDeliveryTaskId.P01, MutationDeliveryPhase.PLANNING, "Classify semantic and external mutation operations", module(ModuleId.CHANGE_CONTRACT), MutationDeliveryTaskId.F01),
        task(MutationDeliveryTaskId.P02, MutationDeliveryPhase.PLANNING, "Define stable plan and expected-file proof", module(ModuleId.CHANGE_CONTRACT), MutationDeliveryTaskId.P01),
        task(MutationDeliveryTaskId.P03, MutationDeliveryPhase.PLANNING, "Create durable plan journal", modules(ModuleId.CHANGE_JOURNAL_CONTRACT, ModuleId.CHANGE_JOURNAL_SQLITE), MutationDeliveryTaskId.P02, MutationDeliveryTaskId.F03),
        task(MutationDeliveryTaskId.P04, MutationDeliveryPhase.PLANNING, "Extract IntelliJ semantic planning adapter", module(ModuleId.CHANGE_PLAN_INTELLIJ), MutationDeliveryTaskId.P02, MutationDeliveryTaskId.F03),
        task(MutationDeliveryTaskId.P05, MutationDeliveryPhase.PLANNING, "Assemble deterministic plans", module(ModuleId.CHANGE_PLAN_SERVICE), MutationDeliveryTaskId.P03, MutationDeliveryTaskId.P04),
        task(MutationDeliveryTaskId.A01, MutationDeliveryPhase.APPLY, "Establish logical workspace mutation lease", module(ModuleId.WORKSPACE_MUTATION_SERVICE), MutationDeliveryTaskId.P05, MutationDeliveryTaskId.F03),
        task(MutationDeliveryTaskId.A02, MutationDeliveryPhase.APPLY, "Revalidate plan, selector, hashes, ownership, and provenance", module(ModuleId.CHANGE_APPLY_SERVICE), MutationDeliveryTaskId.A01, MutationDeliveryTaskId.P04),
        task(MutationDeliveryTaskId.A03, MutationDeliveryPhase.APPLY, "Prepare writable-target and recovery capabilities", module(ModuleId.CHANGE_RECOVERY_SERVICE), MutationDeliveryTaskId.A02),
        task(MutationDeliveryTaskId.A04, MutationDeliveryPhase.APPLY, "Validate public IntelliJ refactoring APIs on supported builds", module(ModuleId.CHANGE_APPLY_INTELLIJ), MutationDeliveryTaskId.A03),
        task(MutationDeliveryTaskId.A05, MutationDeliveryPhase.APPLY, "Implement short PSI/refactoring write-command executor", module(ModuleId.CHANGE_APPLY_INTELLIJ), MutationDeliveryTaskId.A04),
        task(MutationDeliveryTaskId.A06, MutationDeliveryPhase.APPLY, "Implement external file writer with typed target capability", module(ModuleId.CHANGE_APPLY_FILESYSTEM), MutationDeliveryTaskId.A03),
        task(MutationDeliveryTaskId.A07, MutationDeliveryPhase.APPLY, "Persist affected documents and capture after-images", module(ModuleId.CHANGE_APPLY_SERVICE), MutationDeliveryTaskId.A05, MutationDeliveryTaskId.A06),
        task(MutationDeliveryTaskId.A08, MutationDeliveryPhase.APPLY, "Prove declared write-set closure", module(ModuleId.CHANGE_APPLY_SERVICE), MutationDeliveryTaskId.A07),
        task(MutationDeliveryTaskId.V01, MutationDeliveryPhase.TRANSITION_AND_VERIFICATION, "Route targeted post-write workspace transition", module(ModuleId.WORKSPACE_SERVICE), MutationDeliveryTaskId.A08),
        task(MutationDeliveryTaskId.V02, MutationDeliveryPhase.TRANSITION_AND_VERIFICATION, "Publish one resulting semantic generation", modules(ModuleId.WORKSPACE_SERVICE, ModuleId.EVIDENCE_SQLITE), MutationDeliveryTaskId.V01),
        task(MutationDeliveryTaskId.V03, MutationDeliveryPhase.TRANSITION_AND_VERIFICATION, "Evaluate diagnostics and operation-specific postconditions", module(ModuleId.CHANGE_VERIFY_INTELLIJ), MutationDeliveryTaskId.V02),
        task(MutationDeliveryTaskId.V04, MutationDeliveryPhase.TRANSITION_AND_VERIFICATION, "Reconcile expected and observed semantic delta", module(ModuleId.CHANGE_VERIFY_SERVICE), MutationDeliveryTaskId.V03),
        task(MutationDeliveryTaskId.V05, MutationDeliveryPhase.TRANSITION_AND_VERIFICATION, "Issue terminal verified receipt", modules(ModuleId.CHANGE_VERIFY_SERVICE, ModuleId.CHANGE_JOURNAL_CONTRACT), MutationDeliveryTaskId.V04),
        task(MutationDeliveryTaskId.R01, MutationDeliveryPhase.RECOVERY, "Implement automatic rollback policy", module(ModuleId.CHANGE_RECOVERY_SERVICE), MutationDeliveryTaskId.A07, MutationDeliveryTaskId.V04),
        task(MutationDeliveryTaskId.R02, MutationDeliveryPhase.RECOVERY, "Reconcile and prove rollback generation", modules(ModuleId.CHANGE_RECOVERY_SERVICE, ModuleId.CHANGE_VERIFY_SERVICE), MutationDeliveryTaskId.R01, MutationDeliveryTaskId.V01, MutationDeliveryTaskId.V02),
        task(MutationDeliveryTaskId.R03, MutationDeliveryPhase.RECOVERY, "Resume or recover after crash", module(ModuleId.CHANGE_RECOVERY_SERVICE), MutationDeliveryTaskId.P03, MutationDeliveryTaskId.A03, MutationDeliveryTaskId.R01),
        task(MutationDeliveryTaskId.M01, MutationDeliveryPhase.MIGRATION, "Route rename through plan/apply/verify", module(ModuleId.RUNTIME_COMPOSITION), MutationDeliveryTaskId.V05, MutationDeliveryTaskId.R03),
        task(MutationDeliveryTaskId.M02, MutationDeliveryPhase.MIGRATION, "Route replace/add/implementation/body/import operations", module(ModuleId.RUNTIME_COMPOSITION), MutationDeliveryTaskId.M01),
        task(MutationDeliveryTaskId.M03, MutationDeliveryPhase.MIGRATION, "Remove semantic access to generic raw apply-edits", modules(ModuleId.RUNTIME_SERVER, ModuleId.PROTOCOL_REGISTRY), MutationDeliveryTaskId.M02, MutationDeliveryTaskId.F03),
        task(MutationDeliveryTaskId.M04, MutationDeliveryPhase.MIGRATION, "Move workspace-generation storage out of the legacy index-store host", module(ModuleId.EVIDENCE_SQLITE), MutationDeliveryTaskId.V02),
        task(MutationDeliveryTaskId.T01, MutationDeliveryPhase.PROOF, "Contract and state-machine suite", modules(ModuleId.CHANGE_CONTRACT, ModuleId.CHANGE_JOURNAL_CONTRACT, ModuleId.CHANGE_RECOVERY_CONTRACT), MutationDeliveryTaskId.F01, MutationDeliveryTaskId.P02, MutationDeliveryTaskId.V05, MutationDeliveryTaskId.R03),
        task(MutationDeliveryTaskId.T02, MutationDeliveryPhase.PROOF, "IntelliJ write-protocol integration suite", modules(ModuleId.CHANGE_APPLY_INTELLIJ, ModuleId.CHANGE_PLAN_INTELLIJ, ModuleId.CHANGE_VERIFY_INTELLIJ), MutationDeliveryTaskId.A05, MutationDeliveryTaskId.A07, MutationDeliveryTaskId.V03),
        task(MutationDeliveryTaskId.T03, MutationDeliveryPhase.PROOF, "Concurrency, movement, and recovery fault suite", modules(ModuleId.CHANGE_RECOVERY_SERVICE, ModuleId.WORKSPACE_SERVICE), MutationDeliveryTaskId.R03, MutationDeliveryTaskId.V02),
        task(MutationDeliveryTaskId.T04, MutationDeliveryPhase.PROOF, "Performance and UI-safety suite", module(ModuleId.INDEXER), MutationDeliveryTaskId.T02, MutationDeliveryTaskId.T03),
        task(MutationDeliveryTaskId.T05, MutationDeliveryPhase.PROOF, "Enterprise multi-module mutation demonstration", MutationDeliveryOwner.EndToEndCorpus, MutationDeliveryTaskId.M03, MutationDeliveryTaskId.T01, MutationDeliveryTaskId.T02, MutationDeliveryTaskId.T03, MutationDeliveryTaskId.T04),
    )
    // @formatter:on

    private fun task(
        id: MutationDeliveryTaskId,
        phase: MutationDeliveryPhase,
        name: String,
        owner: MutationDeliveryOwner,
        vararg dependencies: MutationDeliveryTaskId,
    ): MutationDeliveryTaskPolicy = MutationDeliveryTaskPolicy(
        id = id,
        phase = phase,
        name = name,
        lifecycle = MutationDeliveryTaskLifecycle.OPEN,
        dependsOn = dependencies.toSet(),
        owner = owner,
    )

    private fun module(id: ModuleId): MutationDeliveryOwner = MutationDeliveryOwner.Modules(setOf(id))

    private fun modules(vararg ids: ModuleId): MutationDeliveryOwner = MutationDeliveryOwner.Modules(ids.toSet())
}
