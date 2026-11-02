plugins {
    id("com.vanniktech.maven.publish")
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.lucida.android"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":lucida-core"))
    // Robolectric (MIT) runs View, Canvas and Base64 on the JVM: no emulator.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(kotlin("test-junit"))
    // Optional: add it to your app to enable install attribution (Apache-2.0).
    compileOnly("com.android.installreferrer:installreferrer:2.2")
}

mavenPublishing {
    coordinates("io.github.lucidaanalytics", "lucida-android", "0.1.0")
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("lucida-android")
        description.set("Lucida Android SDK: Android library")
    }
}
