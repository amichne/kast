import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
    `java-library`
    `maven-publish`
    signing
    id("com.vanniktech.maven.publish")
}

val extension = extensions.create<KastPublishingExtension>("kastPublishing")

extensions.configure<MavenPublishBaseExtension> {
    publishToMavenCentral(
        automaticRelease = true,
        validateDeployment = true,
    )
}

afterEvaluate {
    val artifactId = requireNotNull(extension.artifactId.orNull) {
        "kastPublishing.artifactId must be set"
    }
    val moduleName = requireNotNull(extension.moduleName.orNull) {
        "kastPublishing.moduleName must be set"
    }
    val moduleDescription = requireNotNull(extension.moduleDescription.orNull) {
        "kastPublishing.moduleDescription must be set"
    }

    require(artifactId.isNotBlank()) {
        "kastPublishing.artifactId must be set"
    }
    require(moduleName.isNotBlank()) {
        "kastPublishing.moduleName must be set"
    }
    require(moduleDescription.isNotBlank()) {
        "kastPublishing.moduleDescription must be set"
    }

    configureKastPublishing(
        artifactId = artifactId,
        moduleName = moduleName,
        moduleDescription = moduleDescription,
    )
}
