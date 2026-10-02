package io.github.amichne.kast.runtime.hosted

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.extensions.PluginId
import io.github.amichne.kast.kernel.Refinement
import io.github.amichne.kast.protocol.contract.HostedCompatibilityDocument
import io.github.amichne.kast.protocol.contract.IdeHostCompatibilityPolicy
import io.github.amichne.kast.protocol.wire.CanonicalHostedContract
import java.util.Properties

/** Packaged provenance belongs to this loaded plugin and never depends on control installation files. */
internal object HostedCompatibilityMetadata {
    val document: HostedCompatibilityDocument by lazy {
        val properties = Properties()
        checkNotNull(javaClass.getResourceAsStream("/kast-hosted-query.properties")).use(properties::load)
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
