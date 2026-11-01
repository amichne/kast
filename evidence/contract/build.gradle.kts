plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.evidence"

base {
    archivesName.set("evidence-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
}
