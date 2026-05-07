import com.vanniktech.maven.publish.MavenPublishBaseExtension
import io.amichne.konditional.gradle.KonditionalPublishingExtension
import io.amichne.konditional.gradle.configureKonditionalPublishing
import org.gradle.kotlin.dsl.configure

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish")
}

val extension = extensions.create<KonditionalPublishingExtension>("konditionalPublishing")
extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(
        automaticRelease = true,
        validateDeployment = true,
    )
}

afterEvaluate {
    val artifactId = requireNotNull(extension.artifactId.orNull) {
        "konditionalPublishing.artifactId must be set"
    }
    val moduleName = requireNotNull(extension.moduleName.orNull) {
        "konditionalPublishing.moduleName must be set"
    }
    val moduleDescription = requireNotNull(extension.moduleDescription.orNull) {
        "konditionalPublishing.moduleDescription must be set"
    }

    require(artifactId.isNotBlank()) {
        "konditionalPublishing.artifactId must be set"
    }
    require(moduleName.isNotBlank()) {
        "konditionalPublishing.moduleName must be set"
    }
    require(moduleDescription.isNotBlank()) {
        "konditionalPublishing.moduleDescription must be set"
    }

    configureKonditionalPublishing(
        artifactId = artifactId,
        moduleName = moduleName,
        moduleDescription = moduleDescription,
    )
}
