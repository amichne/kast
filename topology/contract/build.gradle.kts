plugins {
    id("kast.kotlin-library")
    id("kast.role.contract")
}

group = "${providers.gradleProperty("GROUP").get()}.topology"

base {
    archivesName.set("topology-contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":workspace:contract"))
    api(project(":symbol:contract"))
    testImplementation(testFixtures(project(":workspace:contract")))
}
