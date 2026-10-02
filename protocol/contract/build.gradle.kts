plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

dependencies {
    api(project(":kernel"))
    implementation(project(":symbol:contract"))
}

tasks.processResources {
    val values =
        mapOf(
            "ideBuild" to libs.versions.ide.host.build.get(),
            "kotlinPluginBuild" to libs.versions.ide.kotlin.plugin.build.get(),
        )
    inputs.properties(values)
    filesMatching("kast-hosted-platform.properties") { expand(values) }
}
