package support.architecture

/**
 * Installation-relative path literals belong to `InstallationLayout` in `:distribution:contract`.
 *
 * The scanner reports [ForbiddenEffect.INSTALLATION_PATH_DERIVATION] when a compiled path derivation receives a
 * compile-time string operand whose shape names the installation layout. The operand is the constant loaded directly
 * before the call, or the literal prefix of an indy string-concatenation recipe, so `resolve("config/environment")`,
 * `resolve("state/broker/$profile/...")`, and `Path.of("share/kast/...")` are all observed. A literal stored in a
 * local or passed through a parameter is not observed; the rule proves the absence of new literal call sites, not of
 * every derived path.
 */
internal object InstallationPathDerivationRule {
    private val derivationMethods = setOf(
        JvmMember.of("java/nio/file/Path", "resolve", "(Ljava/lang/String;)Ljava/nio/file/Path;"),
        JvmMember.of("java/nio/file/Path", "resolveSibling", "(Ljava/lang/String;)Ljava/nio/file/Path;"),
        JvmMember.of("java/nio/file/Path", "endsWith", "(Ljava/lang/String;)Z"),
        JvmMember.of("java/nio/file/Path", "startsWith", "(Ljava/lang/String;)Z"),
        JvmMember.of("java/nio/file/Path", "of", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"),
        JvmMember.of("java/nio/file/Paths", "get", "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"),
    )

    /** First path segments that only the installation layout and its management root use. */
    private val installationSegments = setOf("config", "state", "share", "installation", "recovery")

    /** Installation and management-root record names that are installation-relative wherever they appear. */
    private val installationRecords = setOf(
        "installation.json",
        "management.json",
        "management.lock",
        "activation.lock",
        "workspaces.json",
        "selected-ide.json",
        "epoch.json",
        "product-selection.json",
    )

    /**
     * Proof transition: `(JvmMember, String?) -> Boolean`.
     *
     * Establishes whether one compiled call derives an installation-relative path from a constant operand. The
     * mapping is total; a call with no constant operand is never a derivation under this rule.
     */
    fun derives(target: JvmMember, operand: String?): Boolean =
        operand != null && target in derivationMethods && installationRelative(operand)

    internal fun installationRelative(literal: String): Boolean =
        literal.substringBefore('/') in installationSegments || literal in installationRecords
}

/** The exact compiled callers that may derive these path shapes. */
internal object InstallationPathDerivationOwners {
    private const val CONTRACT = "io/github/amichne/kast/distribution/contract"
    private const val CLI = "io/github/amichne/kast/distribution/cli"

    /** The layout owner itself. */
    val layout: Set<JvmClassName> = setOf(
        JvmClassName("$CONTRACT/InstallationLayout"),
        JvmClassName("$CONTRACT/InstallationLayout\$Companion"),
        JvmClassName("$CONTRACT/InstallationLayout\$Configuration"),
        JvmClassName("$CONTRACT/InstallationLayout\$State"),
        JvmClassName("$CONTRACT/InstallationLayout\$Management"),
    )

    /**
     * Workspace container profiles reuse the `config` and `state` names beneath their own profile directory, not
     * beneath the installation (`docs/reviews/go-installation-lifecycle-design.md`, section 1.2).
     */
    val containerProfile: Set<JvmClassName> = setOf(
        JvmClassName("$CLI/AdmittedContainer"),
        JvmClassName("$CLI/ContainerStore"),
        JvmClassName("$CLI/ContainerStoreKt"),
        JvmClassName("$CLI/ContainerStateAdmissionKt"),
        JvmClassName("$CLI/ManagedWorkspaceRemovalKt"),
        JvmClassName("$CLI/RejectedWorkspaceRemovalKt"),
        JvmClassName("$CLI/WorkspaceSeedingKt"),
    )
}
