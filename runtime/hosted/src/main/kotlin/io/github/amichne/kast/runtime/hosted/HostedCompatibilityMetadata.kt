package io.github.amichne.kast.runtime.hosted

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.AdmittedIdeHostCompatibility
import io.github.amichne.kast.protocol.contract.DeclaredHostCompatibilityPolicy
import io.github.amichne.kast.protocol.contract.DeclaredIntellijTargets
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityAdmission
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityFailure
import io.github.amichne.kast.protocol.contract.IntellijTargetFailure
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.util.Properties

/** Packaged provenance belongs to this loaded plugin and never depends on control installation files. */
internal object HostedCompatibilityMetadata {
    private val properties =
        Properties().also { values ->
            checkNotNull(javaClass.getResourceAsStream("/kast-hosted-query.properties")).use(values::load)
        }

    val buildIdentity: IdeHostBuildIdentity =
        IdeHostBuildIdentity(
                checkNotNull(properties.getProperty("version")),
                checkNotNull(properties.getProperty("sourceRevision")),
                checkNotNull(properties.getProperty("sourceTree")),
            )
            .also {
                check(Regex("[0-9a-f]{40}").matches(it.sourceRevision))
                check(Regex("[0-9a-f]{40}").matches(it.sourceTree))
            }

    private val observed: HostedCompatibilityDocument by lazy {
        HostedCompatibilityDocument(
            ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
            checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))).version,
            checkNotNull(properties.getProperty("version")),
            CanonicalHostedContract.document,
        )
    }

    /** The packaged SDK establishes the policy; the runtime cannot define a policy from its own observation. */
    val admission: Refinement<AdmittedIdeHostCompatibility, HostedRuntimeCompatibilityFailure> by lazy {
        val targets =
            when (val parsed = DeclaredIntellijTargets.packaged) {
                is Refinement.Refined -> parsed.value
                is Refinement.Rejected ->
                    return@lazy Refinement.Rejected(HostedRuntimeCompatibilityFailure.TargetRegistry(parsed.failure))
            }
        val compiled =
            HostedCompatibilityDocument(
                checkNotNull(properties.getProperty("ideBuild")),
                checkNotNull(properties.getProperty("kotlinBuild")),
                checkNotNull(properties.getProperty("version")),
                CanonicalHostedContract.document,
            )
        when (val defined = DeclaredHostCompatibilityPolicy.define(targets, compiled)) {
            is Refinement.Refined ->
                when (val actual = defined.value.admit(observed.candidate())) {
                    is IdeHostCompatibilityAdmission.Admitted -> Refinement.Refined(actual.compatibility)
                    is IdeHostCompatibilityAdmission.Rejected ->
                        Refinement.Rejected(HostedRuntimeCompatibilityFailure.Compatibility(actual.failure))
                }
            is Refinement.Rejected ->
                Refinement.Rejected(HostedRuntimeCompatibilityFailure.Compatibility(defined.failure))
        }
    }

    val document: HostedCompatibilityDocument
        get() {
            check(admission is Refinement.Refined) { "Host runtime compatibility rejected: $admission" }
            return observed
        }
}

internal sealed interface HostedRuntimeCompatibilityFailure {
    data class TargetRegistry(val reason: IntellijTargetFailure) : HostedRuntimeCompatibilityFailure

    data class Compatibility(val reason: IdeHostCompatibilityFailure) : HostedRuntimeCompatibilityFailure
}
