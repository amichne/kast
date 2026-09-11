package conventions

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
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
                    exclude(
                        "**/build/**",
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
