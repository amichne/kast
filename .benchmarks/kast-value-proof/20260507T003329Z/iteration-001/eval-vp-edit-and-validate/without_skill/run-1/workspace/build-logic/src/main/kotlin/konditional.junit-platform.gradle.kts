import org.gradle.api.tasks.testing.Test

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
