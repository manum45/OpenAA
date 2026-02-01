// build.gradle.kts

plugins {
    // Android plugins should come first
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    // Apply the Protobuf plugin. This is what enables the 'proto' source set.
    alias(libs.plugins.protobuf)
}

// 4. PROTOBUF CONFIGURATION BLOCK
// This block must be at the top level.
protobuf {
    protoc {
        // It's recommended to use the same minor version as your dependencies
        artifact = "com.google.protobuf:protoc:3.25.3"
    }

    generateProtoTasks {
        ofSourceSet("main").forEach { task ->
            task.plugins {
                java { }
                kotlin { }
            }
        }
    }
}

android {
    namespace = "io.github.manum45.openaa"
    compileSdk = 36 // Simplified version declaration

    // The Android sourceSets block does NOT need the proto configuration.
    // The top-level one handles it. The Android plugin will automatically
    // pick up the generated code from the build/generated directory.

    defaultConfig {
        applicationId = "io.github.manum45.openaa"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}


dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation("org.bouncycastle:bcpkix-jdk15on:1.70")

    // Make sure your protobuf dependencies match the 'protoc' compiler version
    implementation("com.google.protobuf:protobuf-java:3.25.3")
    implementation("com.google.protobuf:protobuf-kotlin:3.25.3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
