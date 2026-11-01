plugins {
    id("kast.kotlin-serialization")
    id("kast.role.transport")
}

group = "${providers.gradleProperty("GROUP").get()}.distribution"

base {
    archivesName.set("distribution-output")
}

dependencies {
    api(project(":distribution:contract"))
    implementation(libs.serialization.json)
    implementation(libs.jtoon)
    testImplementation(libs.jtoon)
}
