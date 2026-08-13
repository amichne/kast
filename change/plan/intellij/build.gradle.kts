plugins {
    id("kast.kotlin-library")
    id("kast.role.intellij-read")
}

group = "${rootProject.group}.change.plan"

base {
    archivesName.set("change-plan-intellij")
}

dependencies {
    implementation(project(":change:contract"))
    implementation(project(":change:plan:spi"))
    implementation(project(":workspace:contract"))
}
