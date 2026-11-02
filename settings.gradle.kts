pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lucida-android-sdk"
include(":lucida-core")
include(":lucida-openfeature")

// The Android library needs an Android SDK; the pure-JVM core builds and tests without one.
val sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    ?: "${System.getProperty("user.home")}/Library/Android/sdk".takeIf { file(it).isDirectory }
if (sdkDir != null) {
    include(":lucida-android")
    include(":lucida-compose")
}
