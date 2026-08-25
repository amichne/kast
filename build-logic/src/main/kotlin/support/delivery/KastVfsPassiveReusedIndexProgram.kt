package support.delivery

object KastVfsPassiveReusedIndexProgram {
    const val TARGET_HEAD = "78262728313c90bb847e73425dc1a76d704397db"
    val SUPERSEDED_REQUIREMENT_FINGERPRINT =
        Sha256("55c85fff16fc94df8147da27791bbcd082cf55afef6e98fc5f9b061ab8d5162e")
    val REQUIREMENT_FINGERPRINT =
        Sha256("de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c")
    internal val persistedGoalSourcePath =
        AuthoritySourcePath("gradle/delivery/authority-sources/persisted-goal.txt")
    internal val authoritySourceCandidates = listOf(
        persistedGoalSourcePath,
        AuthoritySourcePath(
            "gradle/delivery/authority-sources/superseded-clean-slate-task-graph.json",
        ),
        AuthoritySourcePath(
            "gradle/delivery/authority-sources/superseded-clean-slate-plan.md",
        ),
        AuthoritySourcePath("gradle/delivery/authority-sources/intellij-substrate-program.html"),
    )
    internal val authorityLedgerOutputPath =
        AuthorityArtifactPath("build/reports/delivery/KVP-001-authority-ledger.json")
    internal val authorityContradictionOutputPath =
        AuthorityArtifactPath("build/reports/delivery/KVP-001-contradictions.md")
    internal val authorityVerificationOutputPath =
        AuthorityArtifactPath("build/reports/delivery/KVP-001-authority.json")

    val definition: DeliveryProgram = DeliveryProgram(
        schemaVersion = 1,
        id = ProgramId("kast-vfs-passive-reused-index"),
        name = "Kast best-case VFS-passive reused-index delivery program",
        targetHead = TARGET_HEAD,
        requirementFingerprint = REQUIREMENT_FINGERPRINT,
        sourceDigests = mapOf(
            "deliveryAuthority" to
                Sha256("de2565f0efb71373758bcf89279f4dcc61f9251e44d425bc9559067e2baac11c"),
            "intellijSubstrateProgram" to
                Sha256("7827929f5b8e0bb4248d2135a7382834045c8158cec2a55c2a1933a7220a6b50"),
            "supersededCleanSlateGraph" to
                Sha256("a926effde75fa956c85e33180f77d0cdbdeaf1980ae37259eb2234b9e3ae200c"),
            "supersededCleanSlatePlan" to
                Sha256("797c16ff7264010723e9b7bb2a4e02fe276cb7788bbaea6c5595c295d7a5e361"),
        ),
        requirements = deliveryRequirements(),
        modules = deliveryModules(),
        authorities = deliveryAuthorities(),
        effects = deliveryEffects(),
        tasks = deliveryTasksM0M1() + deliveryTasksM2() + deliveryTasksM3M5(),
        specialEdges = deliverySpecialEdges(),
        processNodes = deliveryProcessNodes(),
        processTransitions = deliveryProcessTransitions(),
        gates = deliveryGates(),
        installedMetrics = deliveryInstalledMetrics(),
        terminalTask = TaskId("KVP-043"),
    )

    val validated: ValidatedProgram by lazy { definition.validate() }
}
