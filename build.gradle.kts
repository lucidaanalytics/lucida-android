// The publish plugin's own dependencies would otherwise lift AGP and the Kotlin plugin on the build
// classpath (Gradle takes the highest version), which needs a newer Gradle than the wrapper pins.
buildscript {
    configurations.classpath {
        resolutionStrategy {
            force("com.android.tools.build:gradle:8.7.3")
            eachDependency { if (requested.group == "org.jetbrains.kotlin") useVersion("2.0.21") }
        }
    }
}
plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("plugin.compose") version "2.0.21" apply false
    id("com.android.library") version "9.5.0" apply false
    id("com.vanniktech.maven.publish") version "0.30.0" apply false
}
