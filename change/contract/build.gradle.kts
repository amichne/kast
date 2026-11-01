plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.change"

base {
    archivesName.set("change-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
    api(project(":symbol:contract"))
    api(project(":relation:contract"))
    api(project(":traversal:contract"))
    api(project(":diagnostic:contract"))
}
