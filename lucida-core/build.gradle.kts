plugins {
    id("com.vanniktech.maven.publish")
    kotlin("jvm")
}


dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = false }
}

mavenPublishing {
    coordinates("io.github.lucidaanalytics", "lucida-core", "0.1.0")
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("lucida-core")
        description.set("Lucida Android SDK: pure-JVM core (capture, identity, consent, flags)")
    }
}
