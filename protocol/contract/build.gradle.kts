plugins {
    id("kast.kotlin-serialization")
    id("kast.role.contract")
}

dependencies {
    api(project(":kernel"))
    implementation(project(":symbol:contract"))
}
