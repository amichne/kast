import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.get
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningExtension
import java.util.Locale

private val githubRepoRegex = Regex("github\\.com[:/](.+?)(\\.git)?$")

private enum class PublishTarget {
    Local,
    Snapshot,
    Release,
    Github,
    Unspecified,
}

private fun Project.resolvePublishTarget(): PublishTarget = when (
    rootProject.findProperty("kast.publish.target")
        ?.toString()
        ?.trim()
        ?.lowercase(Locale.ROOT)
) {
    "local" -> PublishTarget.Local
    "snapshot" -> PublishTarget.Snapshot
    "release" -> PublishTarget.Release
    "github" -> PublishTarget.Github
    else -> PublishTarget.Unspecified
}

private fun normalizeGithubRepository(value: String): String? {
    val trimmed = value.trim()
    val match = githubRepoRegex.find(trimmed)
    val candidate = match?.groupValues?.get(1)
        ?: trimmed.takeIf { it.count { char -> char == '/' } == 1 && !it.contains("github.com") }
    return candidate?.removeSuffix(".git")?.trim()?.takeIf(String::isNotBlank)
}

internal fun Project.resolveKastProjectVersion(props: Map<String, *>): String =
    providers.gradleProperty("version").orNull
        ?: providers.gradleProperty("VERSION").orNull
        ?: (props["version"] as? String)
        ?: (props["VERSION"] as? String)
        ?: rootProject.version.toString().takeIf(String::isNotBlank)
        ?: error("Missing project version. Pass -Pversion=... or create a v-prefixed Git tag.")

internal fun deriveKastModuleName(artifactId: String): String =
    artifactId
        .split('-', '_', '.')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { token ->
            token.lowercase(Locale.ROOT)
                .replaceFirstChar { char ->
                    if (char.isLowerCase()) {
                        char.titlecase(Locale.ROOT)
                    } else {
                        char.toString()
                    }
                }
        }
        .joinToString(separator = " ")

fun Project.configureKastPublishing(
    artifactId: String,
    moduleName: String,
    moduleDescription: String,
) {
    val publishTarget = resolvePublishTarget()
    val props = rootProject.properties
    val projectVersion = resolveKastProjectVersion(props)
    val githubRepository = sequenceOf(
        props["GITHUB_REPOSITORY"] as? String,
        props["gpr.repo"] as? String,
        System.getenv("GITHUB_REPOSITORY"),
        props["POM_SCM_URL"] as? String,
        props["POM_URL"] as? String,
    )
        .mapNotNull { it?.trim() }
        .mapNotNull(::normalizeGithubRepository)
        .firstOrNull()

    val githubUser = (props["gpr.user"] as? String)?.trim()
        ?: System.getenv("GITHUB_ACTOR")
        ?: System.getenv("GITHUB_USERNAME")

    val githubToken = (props["gpr.key"] as? String)?.trim()
        ?: System.getenv("GITHUB_TOKEN")
        ?: System.getenv("GITHUB_PACKAGES_TOKEN")

    extensions.configure<PublishingExtension> {
        publications {
            val publication =
                findByName("maven") as? MavenPublication
                    ?: create<MavenPublication>("maven").apply {
                        from(components["java"])
                    }

            publication.apply {
                groupId = props["GROUP"] as String
                this.artifactId = artifactId
                version = projectVersion

                pom {
                    name.set(moduleName)
                    description.set(moduleDescription)
                    url.set(props["POM_URL"] as String)

                    scm {
                        url.set(props["POM_SCM_URL"] as String)
                        connection.set(props["POM_SCM_CONNECTION"] as String)
                        developerConnection.set(props["POM_SCM_DEV_CONNECTION"] as String)
                    }
                }
            }
        }
        repositories {
            if (!githubRepository.isNullOrBlank() && publishTarget != PublishTarget.Local) {
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/$githubRepository")
                    if (!githubUser.isNullOrBlank() && !githubToken.isNullOrBlank()) {
                        credentials {
                            username = githubUser
                            password = githubToken
                        }
                    }
                }
            }
        }
    }

    extensions.configure<SigningExtension> {
        val signingRequired = publishTarget != PublishTarget.Local
        val useGpgCmd = props.containsKey("signing.gnupg.keyName") ||
            System.getenv("SIGNING_GPG_KEY_NAME") != null
        val hasKeyringCredentials = props.containsKey("signing.keyId") ||
            System.getenv("SIGNING_KEY_ID") != null

        if (useGpgCmd) {
            useGpgCmd()
        }

        if (signingRequired && (useGpgCmd || hasKeyringCredentials)) {
            sign(extensions.getByType(PublishingExtension::class.java).publications["maven"])
        }
    }

    tasks.withType<GenerateModuleMetadata>().configureEach {
        if (name == "generateMetadataFileForMavenPublication") {
            dependsOn(tasks.named("plainJavadocJar"))
        }
    }
}
