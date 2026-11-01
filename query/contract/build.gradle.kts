plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.query"

base {
    archivesName.set("query-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":relation:contract"))
    api(project(":source:contract"))
    api(project(":symbol:contract"))
    api(project(":traversal:contract"))
    api(project(":workspace:contract"))
}
