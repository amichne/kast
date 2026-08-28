plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

group = "${rootProject.group}.distribution"

base {
    archivesName.set("distribution-contract")
}

dependencies {
    api(project(":kernel"))
    api(libs.serialization.json)
}
