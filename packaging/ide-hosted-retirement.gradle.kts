val isolatedRuntimeRetirementVerifier = layout.projectDirectory.file(
    "packaging/verify_no_default_isolated_runtime.py",
)

val verifyHostedPublicInstaller by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves the public installer installs only matched hosted payloads."
    inputs.file(layout.projectDirectory.file("install.sh"))
    inputs.file(layout.projectDirectory.file("packaging/test-installer.sh"))
    outputs.upToDateWhen { false }
    commandLine("bash", layout.projectDirectory.file("packaging/test-installer.sh"))
}

val verifyHostedLocalInstaller by tasks.registering(Exec::class) {
    group = "verification"
    description = "Proves local installation removes legacy runtime payloads."
    inputs.file(layout.projectDirectory.file("packaging/test-install-local.sh"))
    outputs.upToDateWhen { false }
    mustRunAfter("installedProductTest", "verifyIdeHostedRelease")
    commandLine("bash", layout.projectDirectory.file("packaging/test-install-local.sh"))
}

tasks.register<Exec>("verifyNoDefaultIsolatedRuntimeNegative") {
    group = "verification"
    description = "Rejects installer, release, Gradle, and CLI isolated-runtime authorities."
    val report = layout.buildDirectory.file("reports/ide-hosted/KVP-036-negative.json")
    inputs.file(isolatedRuntimeRetirementVerifier)
    outputs.file(report)
    outputs.upToDateWhen { false }
    commandLine(
        "python3",
        isolatedRuntimeRetirementVerifier.asFile,
        "--self-test",
        "--negative-report",
        report.get().asFile,
    )
}

tasks.register<Exec>("verifyNoDefaultIsolatedRuntime") {
    group = "verification"
    description = "Proves the default installation and release have no isolated runtime."
    dependsOn(
        "verifyIdeHostedRelease",
        "installedProductTest",
        verifyHostedPublicInstaller,
        verifyHostedLocalInstaller,
    )
    mustRunAfter("verifyNoDefaultIsolatedRuntimeNegative")
    inputs.file(isolatedRuntimeRetirementVerifier)
    inputs.file(layout.projectDirectory.file("install.sh"))
    inputs.file(layout.projectDirectory.file("build.gradle.kts"))
    inputs.dir(layout.projectDirectory.dir(".github"))
    inputs.dir(layout.projectDirectory.dir("packaging"))
    inputs.dir(layout.projectDirectory.dir("docs"))
    inputs.dir(layout.projectDirectory.dir("cli/src/main"))
    outputs.file(layout.buildDirectory.file("reports/ide-hosted/KVP-036-retirement.json"))
    outputs.upToDateWhen { false }
    commandLine(
        "python3",
        isolatedRuntimeRetirementVerifier.asFile,
        "--root",
        layout.projectDirectory.asFile,
        "--release-directory",
        layout.buildDirectory.dir("release/v${project.version}").get().asFile,
        "--installed-product",
        layout.buildDirectory.dir("installed-product").get().asFile,
        "--report",
        layout.buildDirectory.file(
            "reports/ide-hosted/KVP-036-retirement.json",
        ).get().asFile,
    )
}

tasks.register("verifyDistributionContent") {
    group = "verification"
    description = "Verifies the hosted control-plus-plugin default artifact layouts."
    dependsOn("verifyKastControlDistLayout", "verifyIdeHostedRelease")
}

tasks.register("verifyDistributionSize") {
    group = "verification"
    description = "Enforces the control archive and installed-size ceilings."
    dependsOn("verifyKastControlDistLayout")
}

tasks.register("runtimeDeliveryMvpAcceptance") {
    group = "verification"
    description = "Proves the hosted control-plus-plugin default delivery boundary."
    dependsOn(
        ":cli:check",
        ":ide-plugin:check",
        "verifyIdeHostedReleaseNegative",
        "verifyIdeHostedRelease",
        "verifyDistributionContent",
        "verifyDistributionSize",
        "installedProductTest",
    )
}
