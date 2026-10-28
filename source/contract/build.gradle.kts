plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.source"

base {
    archivesName.set("source-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
    api(project(":symbol:contract"))

    testImplementation(kotlin("test"))
}
