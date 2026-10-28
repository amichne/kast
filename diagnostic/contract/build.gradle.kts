plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.diagnostic"

base {
    archivesName.set("diagnostic-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
}
