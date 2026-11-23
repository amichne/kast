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

    /** Installation, management-root, and share-directory names that are installation-relative wherever they appear. */
    private val installationRecords = setOf(
        "installation.json",
        "management.json",
        "management.lock",
        "activation.lock",
        "workspaces.json",
        "selected-ide.json",
        "epoch.json",
        "product-selection.json",
        "kast.lock",
        "kast-workspaces",
        "kast-runtime-bases",
        ".kast-workspaces.lifetime.lock",
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

/**
 * Upward derivations from installation paths belong to `InstallationLayout` in `:distribution:contract`.
 *
 * The scanner reports [ForbiddenEffect.INSTALLATION_PARENT_DERIVATION] at the second `Path.getParent()` call of a chain
 * whose results feed each other directly, so `kast.parent.parent`, `payload.parent?.parent`, and
 * `kast.toRealPath().parent.parent` are observed. Between two calls the chain survives only the stack duplication and
 * null branch of a safe call, and Kotlin's `Intrinsics.checkNotNull*` assertion with its constant message.
 *
 * Bytecode does not say what a path means, so the rule observes every such chain and admits explicit owners, including
 * the callers whose chains climb a workspace store, an application bundle, or an endpoint directory. A chain split
 * through a local variable, a parameter, a property, or a helper function, and a single `.parent` step, are not
 * observed; the rule proves the absence of new direct chains, not of every upward derivation.
 */
internal object InstallationParentDerivationRule {
    private val parent = JvmMember.of("java/nio/file/Path", "getParent", "()Ljava/nio/file/Path;")
    private const val INTRINSICS = "kotlin/jvm/internal/Intrinsics"
    private const val CHAIN = 2

    /**
     * Proof transition: `(Int, JvmMember) -> Int`.
     *
     * Counts the consecutive parent calls after [target]. A parent call extends the chain, a Kotlin null assertion
     * keeps it, and every other call ends it.
     */
    fun next(parents: Int, target: JvmMember): Int =
        when {
            target == parent -> parents + 1
            target.owner.internalName == INTRINSICS && target.name.value.startsWith("checkNotNull") -> parents
            else -> 0
        }

    /** Whether [target] completed a chain of two parent calls; a longer chain reports once. */
    fun climbs(parents: Int, target: JvmMember): Boolean = target == parent && parents == CHAIN
}

/** The exact compiled callers that may chain `Path.getParent()` calls. */
internal object InstallationParentDerivationOwners {
    private const val CONTRACT = "io/github/amichne/kast/distribution/contract"

    /** The layout owner: the version root of a `bin/kast` path, the user home, and readiness membership. */
    val layout: Set<JvmClassName> = setOf(
        JvmClassName("$CONTRACT/InstallationLayout\$Companion"),
        JvmClassName("$CONTRACT/InstallationLayout\$Management"),
        JvmClassName("$CONTRACT/InstallationLayout\$State"),
    )

    /**
     * Container store owners in `:distribution:cli`: a workspace directory `<store>/containers/<id>` names its store,
     * and a container profile's private home names its profile. Neither climbs an installation root.
     */
    val containerStore: Set<JvmClassName> = setOf(
        JvmClassName("io/github/amichne/kast/distribution/cli/AdmittedWorkspace"),
        JvmClassName("io/github/amichne/kast/distribution/cli/RejectedWorkspaceRemovalKt"),
    )

    /** `:app-server` owners: an IDE executable names its `.app` bundle, and a login agent names `~/Library`. */
    val appServer: Set<JvmClassName> = setOf(
        JvmClassName("io/github/amichne/kast/appserver/DesktopBundle\$Companion"),
        JvmClassName("io/github/amichne/kast/appserver/OwnedLaunchAgentFile"),
    )

    /** `:distribution:managed` owner: a short socket alias names the private alias root beneath `/tmp`. */
    val endpointAlias: Set<JvmClassName> = setOf(
        JvmClassName("io/github/amichne/kast/distribution/managed/endpoint/InstalledEndpointAliases"),
    )

    /** `:runtime:hosted` owner: an endpoint directory names its private namespace beneath `/tmp`. */
    val hostedEndpoint: Set<JvmClassName> = setOf(
        JvmClassName("io/github/amichne/kast/runtime/hosted/OwnedHostedEndpoint\$Companion"),
    )
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
        JvmClassName("$CONTRACT/InstallationLayout\$Share"),
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
