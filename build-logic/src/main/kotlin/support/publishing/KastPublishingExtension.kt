import org.gradle.api.provider.Property

abstract class KastPublishingExtension {
    abstract val artifactId: Property<String>
    abstract val moduleName: Property<String>
    abstract val moduleDescription: Property<String>
}
