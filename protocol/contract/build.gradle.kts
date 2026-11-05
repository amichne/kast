import support.tasks.idea.defaultIntellijTargetProfile

plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

dependencies {
    api(project(":kernel"))
    implementation(project(":symbol:contract"))
}

val defaultTarget = defaultIntellijTargetProfile(project)

tasks.processResources {
    from(project.isolated.rootProject.projectDirectory.file("config/intellij-targets.json"))
    val values =
        mapOf(
            "ideBuild" to defaultTarget.ideBuild,
            "kotlinPluginBuild" to defaultTarget.kotlinPluginBuild,
        )
    inputs.properties(values)
    filesMatching("kast-hosted-platform.properties") { expand(values) }
}
