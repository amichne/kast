import org.gradle.jvm.tasks.Jar

plugins {
    id("kast.runtime-app")
    id("kast.role.indexer-host")
}

extra["kastIncludeShadowJar"] = "true"

application {
    applicationName = "kast-indexer"
    mainClass = "io.github.amichne.kast.indexer.KastIndexerMainKt"
}

dependencies {
    implementation(project(":runtime:composition"))
}

tasks.named<WriteWrapperScriptTask>("writeWrapperScript") {
    outputFile.set(layout.buildDirectory.file("scripts/kast-indexer"))
}

tasks.named<Jar>("jar") {
    isZip64 = true
}
