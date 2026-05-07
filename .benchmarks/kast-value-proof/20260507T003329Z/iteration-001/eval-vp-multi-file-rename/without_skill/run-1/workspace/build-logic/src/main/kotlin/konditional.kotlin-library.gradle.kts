import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    kotlin("jvm")
    `java-library`
}

group = providers.gradleProperty("GROUP").get()
version = providers.gradleProperty("version")
    .orElse(providers.gradleProperty("VERSION"))
    .get()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-parameters")
        optIn.add("io.amichne.konditional.api.KonditionalInternalApi")
    }
}

java {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
