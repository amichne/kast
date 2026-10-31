package io.github.amichne.kast.runtime.hosted

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostBuildIdentity
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
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

    val document: HostedCompatibilityDocument by lazy {
        val document =
            HostedCompatibilityDocument(
                ApplicationInfo.getInstance().build.asStringWithoutProductCode(),
                checkNotNull(PluginManagerCore.getPlugin(PluginId.getId("org.jetbrains.kotlin"))).version,
                checkNotNull(properties.getProperty("version")),
                CanonicalHostedContract.document,
            )
        check(IdeHostCompatibilityPolicy.define(document.candidate()) is Refinement.Refined)
        document
    }
}
