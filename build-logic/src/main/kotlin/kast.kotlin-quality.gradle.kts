import conventions.KotlinFileLengthTask

plugins {
    id("com.diffplug.spotless")
    id("dev.detekt")
}

val repositoryRoot = project.isolated.rootProject.projectDirectory

spotless {
    kotlin {
        target("src/**/*.kt")
        // The schema generator owns these bytes; verifyPublicQueryGeneration enforces parity.
        targetExclude(
            "src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDocuments.kt",
            "src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolDiscoveryDocuments.kt",
            "src/main/kotlin/io/github/amichne/kast/appserver/query/PublicToolTrace.kt",
            "src/main/kotlin/io/github/amichne/kast/protocol/registry/PublicToolIdentity.kt",
        )
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
    config.setFrom(File(repositoryRoot.asFile, "config/detekt/detekt.yml"))
    baseline.set(
        File(repositoryRoot.asFile, "config/detekt/baselines/${project.path.removePrefix(":").replace(':', '/')}/baseline.xml")
    )
    buildUponDefaultConfig = true
    parallel = true
}

val checkMainKotlinFileLength = tasks.register<KotlinFileLengthTask>("checkMainKotlinFileLength") {
        group = "verification"
        description = "Rejects production Kotlin files larger than the structural reading budget."
        sourceFiles.from(fileTree("src/main") { include("**/*.kt") })
        maximumLines = 400
        baselineFile.set(repositoryRoot.file("config/kotlin/file-length-baseline.tsv"))
    }

val checkTestKotlinFileLength = tasks.register<KotlinFileLengthTask>("checkTestKotlinFileLength") {
        group = "verification"
        description = "Rejects test Kotlin files larger than the test reading budget."
        sourceFiles.from(fileTree("src/test") { include("**/*.kt") })
        maximumLines = 600
        baselineFile.set(repositoryRoot.file("config/kotlin/file-length-baseline.tsv"))
    }

tasks.named("check") {
    // Detekt 2.0.0-alpha.6 attaches its aggregate, type-resolved analysis of every source set to check.
    dependsOn(
        "spotlessCheck",
        checkMainKotlinFileLength,
        checkTestKotlinFileLength,
    )
}

tasks.register("scopedCheck") {
    group = "verification"
    description = "Examines this module's format, structure, Detekt findings and unit tests without product assembly."
    dependsOn("spotlessCheck", checkMainKotlinFileLength, checkTestKotlinFileLength, "detekt", "test")
}
