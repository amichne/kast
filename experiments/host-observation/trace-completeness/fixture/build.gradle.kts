plugins { kotlin("jvm") version "2.4.20" }
repositories { mavenCentral() }
kotlin { jvmToolchain(25) }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.springframework.data:spring-data-commons:4.0.1")
}
