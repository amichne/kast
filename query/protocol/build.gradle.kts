plugins {
    `java-test-fixtures`
    id("kast.kotlin-serialization")
    id("kast.role.service")
}

base.archivesName.set("query-protocol")

dependencies {
    testFixturesApi(project(":relation:contract"))
    testFixturesApi(project(":symbol:contract"))
    testFixturesApi(project(":protocol:contract"))
    testFixturesApi(testFixtures(project(":workspace:contract")))
    implementation(project(":kernel"))
    implementation(project(":protocol:contract"))
    implementation(project(":query:contract"))
    implementation(project(":workspace:contract"))
    implementation(project(":symbol:contract"))
    implementation(project(":source:contract"))
    implementation(project(":diagnostic:contract"))
    implementation(project(":traversal:contract"))
    implementation(project(":relation:contract"))
}
