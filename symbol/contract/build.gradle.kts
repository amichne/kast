plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${rootProject.group}.symbol"

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
}
