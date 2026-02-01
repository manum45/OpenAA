// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    id("com.google.protobuf") version "0.9.6" apply false
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.0") // Use your Android Gradle Plugin version
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.0") // Use your Kotlin version
        classpath("com.google.protobuf:protobuf-gradle-plugin:0.9.4") // Protobuf plugin
    }
}
