plugins {
    kotlin("jvm") version "2.1.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":lib"))
}

application {
    mainClass.set("sample.app.MainKt")
}
