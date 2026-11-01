plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.relation"

base {
    archivesName.set("relation-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":symbol:contract"))
    api(project(":workspace:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))
}
