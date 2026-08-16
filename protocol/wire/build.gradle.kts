plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

dependencies {
    api(project(":kernel"))
    api(project(":protocol:contract"))
    api(project(":protocol:registry"))
    api(libs.serialization.json)
}
