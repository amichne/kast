import conventions.KotlinFileLengthTask

plugins {
    id("com.diffplug.spotless")
    id("dev.detekt")
}

spotless {
    kotlin {
        target("src/**/*.kt")
        // The schema generator owns these bytes; verifyPublicQueryGeneration enforces parity.
        targetExclude("src/main/kotlin/io/github/amichne/kast/appserver/query/PublicQueryDocuments.kt")
        ktfmt("0.64").kotlinlangStyle().configure {
            it.setMaxWidth(120)
        }
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.64").kotlinlangStyle().configure {
            it.setMaxWidth(120)
        }
    }
}

detekt {
    toolVersion = "2.0.0-alpha.6"
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    parallel = true
}

val checkMainKotlinFileLength by tasks.registering(KotlinFileLengthTask::class) {
    group = "verification"
    description = "Rejects production Kotlin files larger than the structural reading budget."
    sourceFiles.from(fileTree("src/main") { include("**/*.kt") })
    maximumLines = 400
}

val checkTestKotlinFileLength by tasks.registering(KotlinFileLengthTask::class) {
    group = "verification"
    description = "Rejects test Kotlin files larger than the test reading budget."
    sourceFiles.from(fileTree("src/test") { include("**/*.kt") })
    maximumLines = 600
}

tasks.named("check") {
    dependsOn(
        "spotlessCheck",
        "detektMain",
        "detektTest",
        checkMainKotlinFileLength,
        checkTestKotlinFileLength,
    )
}
