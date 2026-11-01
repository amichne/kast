plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

group = "${providers.gradleProperty("GROUP").get()}.diagnostic"

base {
    archivesName.set("diagnostic-service")
}

dependencies {
    implementation(project(":diagnostic:contract"))
    implementation(project(":workspace:contract"))
}
