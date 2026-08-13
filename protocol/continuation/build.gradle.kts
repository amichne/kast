plugins {
    id("kast.kotlin-library")
    id("kast.role.service")
}

dependencies {
    implementation(project(":kernel"))
    implementation(project(":workspace:contract"))
}
