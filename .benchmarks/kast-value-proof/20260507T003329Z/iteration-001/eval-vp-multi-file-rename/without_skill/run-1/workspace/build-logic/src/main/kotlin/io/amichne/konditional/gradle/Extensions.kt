package io.amichne.konditional.gradle

import org.gradle.api.provider.Property

abstract class KonditionalPublishingExtension {
    abstract val artifactId: Property<String>
    abstract val moduleName: Property<String>
    abstract val moduleDescription: Property<String>
}
