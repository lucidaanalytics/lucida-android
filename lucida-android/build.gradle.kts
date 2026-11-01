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
}

dependencies {
    api(project(":lucida-core"))
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
