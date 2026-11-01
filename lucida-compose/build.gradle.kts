plugins {
    id("com.vanniktech.maven.publish")
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "com.lucida.compose"
    compileSdk = 34
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    api(project(":lucida-core"))
    // The host app supplies Compose; compileOnly keeps this module from pinning a version (Apache-2.0).
    compileOnly(platform("androidx.compose:compose-bom:2024.10.01"))
    compileOnly("androidx.compose.ui:ui")
}

mavenPublishing {
    coordinates("io.github.lucidaanalytics", "lucida-compose", "0.1.0")
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    pom {
        name.set("lucida-compose")
        description.set("Lucida Android SDK: Jetpack Compose helpers")
    }
}
