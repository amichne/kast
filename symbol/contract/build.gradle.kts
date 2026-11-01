plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.symbol"

base {
    archivesName.set("symbol-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
}
