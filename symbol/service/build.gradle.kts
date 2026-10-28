plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.symbol"

base {
    archivesName.set("symbol-service")
}

dependencies {
    implementation(project(":symbol:contract"))
    implementation(project(":workspace:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))
}
