plugins {
    id("com.vanniktech.maven.publish")
    kotlin("jvm")
}

dependencies {
    api(project(":lucida-core"))
    // Official OpenFeature Kotlin SDK (Apache-2.0); resolves its JVM/Android variant by module metadata.
    api("dev.openfeature:kotlin-sdk:0.6.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = false }
}

mavenPublishing {
    coordinates("io.github.lucidaanalytics", "lucida-openfeature", "0.1.0")
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("lucida-openfeature")
        description.set("OpenFeature provider for the Lucida Android SDK")
    }
}
