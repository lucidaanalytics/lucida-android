plugins {
    id("com.vanniktech.maven.publish")
    kotlin("jvm")
}

dependencies {
    api(project(":lucida-core"))
    // Official OpenFeature Kotlin SDK (Apache-2.0); resolves its JVM/Android variant by module metadata.
    api("dev.openfeature:kotlin-sdk:0.6.2") // 0.7+ is built with Kotlin 2.2, newer than this build reads
    // the SDK publishes coroutines as runtime-only, but observe() exposes a Flow and the provider uses Dispatchers.IO
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = false }
}

mavenPublishing {
    coordinates((findProperty("lucidaGroup") ?: "io.github.lucidaanalytics").toString(), "lucida-openfeature", "0.1.0")
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    if (findProperty("signingInMemoryKey") != null || findProperty("signing.keyId") != null) signAllPublications() // JitPack builds unsigned
    pom {
        name.set("lucida-openfeature")
        description.set("OpenFeature provider for the Lucida Android SDK")
    }
}
