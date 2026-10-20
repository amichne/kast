plugins { kotlin("jvm") version "2.4.21" }

kotlin {
    jvmToolchain(25)
    compilerOptions { allWarningsAsErrors = true }
}

val fixtureRuntime = sourceSets.create("fixtureRuntime") {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += output + compileClasspath
}

configurations[fixtureRuntime.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())

kotlin.sourceSets.named("fixtureRuntime") {
    kotlin.srcDirs("runtime/kotlin", "oracle")
}

kotlin.target.compilations.getByName("fixtureRuntime").associateWith(kotlin.target.compilations.getByName("main"))

tasks.register<JavaExec>("assertFixture") {
    dependsOn("fixtureRuntimeClasses", "testClasses")
    classpath = fixtureRuntime.runtimeClasspath
    mainClass = "fixture.runtime.RuntimeAssertionsKt"
    workingDir = projectDir
}
