plugins {
    id("kast.kotlin-serialization")
    id("kast.role.transport")
}

dependencies {
    api(project(":protocol:contract"))
    api(project(":protocol:wire"))
    implementation(project(":symbol:contract"))
    implementation(project(":topology:contract"))
    implementation(project(":workspace:contract"))
}
