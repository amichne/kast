import org.gradle.api.tasks.JavaExec

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(25) }

// This is an isolated reference build. It is not included as a production Kast module.
val native by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}
val fixture by sourceSets.creating { kotlin.srcDir("fixtures") }
dependencies { add(fixture.implementationConfigurationName, libs.coroutines.core) }

val ideaHome = providers.gradleProperty("ideaHome")
val ideaLibraries = files(ideaHome.map { home -> fileTree(home) {
    include("lib/**/*.jar", "plugins/Kotlin/lib/**/*.jar", "plugins/java/lib/**/*.jar")
    exclude("plugins/Kotlin/lib/jps/**", "plugins/Kotlin/lib/kotlinc/lib/kotlin-compiler.jar")
} })
dependencies { add(native.compileOnlyConfigurationName, ideaLibraries) }

val admitIdea by tasks.registering(Exec::class) {
    commandLine("python3", "verify.py", "idea", ideaHome.orElse("").get(),
        libs.versions.ide.host.build.get(), libs.versions.ide.kotlin.plugin.build.get())
}
tasks.named("compileNativeKotlin") { dependsOn(admitIdea) }

fun referenceTask(name: String, mode: String, filename: String) = tasks.register<JavaExec>(name) {
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("kast.example.binding.ProgramKt")
    val destination = layout.buildDirectory.file(filename)
    args(mode, destination.get().asFile.absolutePath)
    outputs.file(destination)
    outputs.upToDateWhen { false }
}
val verifyReference = referenceTask("verifyReference", "check", "reference.json")
val generateProgram = referenceTask("generateProgram", "graph", "program.json")

tasks.register<JavaExec>("verifyFixture") {
    dependsOn(tasks.named(fixture.classesTaskName))
    classpath = fixture.runtimeClasspath
    mainClass.set("kast.identity.fixture.IdentityFixtureKt")
}

// A receipt cannot be emitted from an older result or a dirty checkout.
val referenceReceipt by tasks.registering(Exec::class) {
    dependsOn(verifyReference, generateProgram)
    val javaLauncher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    doFirst {
        commandLine("python3", "verify.py", "receipt", "build/program.json",
            javaLauncher.get().executablePath.asFile.absolutePath, sourceSets.main.get().runtimeClasspath.asPath)
    }
    outputs.file(layout.buildDirectory.file("identity-proof/model.json"))
    outputs.upToDateWhen { false }
}

tasks.register<Exec>("verifyReferenceReceipt") {
    dependsOn(referenceReceipt)
    commandLine("python3", "verify.py", "receipt-check", "build/identity-proof/model.json")
}

tasks.register<Exec>("verifyDelivery") {
    dependsOn(verifyReferenceReceipt)
    commandLine("python3", "verify.py", "delivery", "build/program.json")
}

tasks.named("check") { dependsOn(verifyReference, tasks.named("verifyFixture")) }

// Tests receipt corruption in an isolated temporary Git repository, not in the caller checkout.
tasks.register<Exec>("verifyReceiptFailures") {
    dependsOn(tasks.named("classes"))
    val launcher = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
    doFirst {
        commandLine("python3", "test_receipts.py", "--java", launcher.get().executablePath.asFile.absolutePath,
            "--classpath", sourceSets.main.get().runtimeClasspath.asPath)
    }
}
