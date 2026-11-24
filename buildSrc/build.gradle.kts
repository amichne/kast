plugins { java }

// Only the IDEA extractor belongs on this parent classloader. Other conventions change independently.
dependencies { implementation("kast.build:idea-libraries") }

repositories {
    maven("https://maven-central.storage-download.googleapis.com/maven2/")
    mavenCentral()
}
