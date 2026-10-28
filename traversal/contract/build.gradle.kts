plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.traversal"

base {
    archivesName.set("traversal-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":relation:contract"))
    api(project(":symbol:contract"))
    api(project(":workspace:contract"))
}
