plugins {
    id("kast.kotlin-library")
    id("kast.role.sqlite")
    id("org.jetbrains.kotlin.plugin.serialization")
}

group = "${rootProject.group}.evidence"

base {
    archivesName.set("evidence-topology-sqlite")
}

dependencies {
    implementation(project(":evidence:contract"))
    implementation(project(":topology:contract"))
    implementation(project(":relation:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)
}

tasks.register<JavaExec>("historicalRead") {
    group = "application"
    description = "Read one historical topology artifact without live workspace access."
    mainClass.set("io.github.amichne.kast.evidence.sqlite.historical.cli.HistoricalOfflineMainKt")
    classpath = sourceSets.main.get().runtimeClasspath
}
