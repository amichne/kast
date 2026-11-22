package conventions

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.TaskProvider

/** Shared registration is exercised by TestKit, including both root gate dependencies and excluded outputs. */
fun Project.registerJsonContractVerification(parserClasspath: FileCollection): TaskProvider<VerifyJsonContractsTask> {
    val verification =
        tasks.register("verifyJsonContracts", VerifyJsonContractsTask::class.java) {
            group = "verification"
            description =
                "Rejects new, changed, duplicated, or stale manual JSON contract expressions using Kotlin PSI syntax evidence."
            repositoryDirectory.set(layout.projectDirectory)
            sourceFiles.from(
                fileTree(layout.projectDirectory) {
                    include("**/*.kt", "**/*.kts")
                    exclude(GradleBuildOutput())
                    exclude(
                        "**/.gradle/**",
                        "**/.kotlin/**",
                        "**/.git/**",
                        "**/.idea/**",
                        "**/out/**",
                        "**/node_modules/**",
                    )
                }
            )
            baselineFile.set(layout.projectDirectory.file("config/json-contracts/baseline.json"))
            reportFile.set(layout.buildDirectory.file("reports/json-contracts/report.json"))
            this.parserClasspath.from(parserClasspath)
        }
    tasks.matching { it.name == "check" || it.name == "productBuildGate" }.configureEach { dependsOn(verification) }
    return verification
}

/**
 * A source module may be named `build`; only the `build` child of a Gradle project directory is output.
 * Mirrors the configuration-ingress scanner rule in packaging/internal/configuration/configuration_ingress.py.
 */
internal class GradleBuildOutput : Spec<FileTreeElement> {
    override fun isSatisfiedBy(element: FileTreeElement): Boolean =
        element.isDirectory &&
            element.name == "build" &&
            GRADLE_PROJECT_MARKERS.any { element.file.parentFile.resolve(it).isFile }
}

private val GRADLE_PROJECT_MARKERS = listOf("build.gradle.kts", "settings.gradle.kts", "build.gradle", "settings.gradle")
