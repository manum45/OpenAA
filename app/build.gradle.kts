import com.google.protobuf.gradle.proto

// build.gradle.kts

plugins {
    // Android plugins should come first
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)

    // Apply the Protobuf plugin. This is what enables the 'proto' source set.
    alias(libs.plugins.protobuf)
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

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Proto DataStore
    //implementation("androidx.datastore:datastore-proto:1.0.0")

    // Protobuf Java runtime (required for schema compilation)
    implementation("com.google.protobuf:protobuf-java:3.24.4")
    implementation("com.google.protobuf:protobuf-kotlin:4.33.5")

}


protobuf {
    // Configure Protobuf code generation
    protoc {
        // It's crucial to specify the compiler artifact
        artifact = "com.google.protobuf:protoc:3.24.4"
    }

    // Configure Protobuf code generation
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // Generate Java classes (required for DataStore)
                create("java") {
                    option("lite") // Use the "lite" runtime for smaller binary size
                }
                create("kotlin") {
                    option("lite") // Use the "lite" runtime for smaller binary size
                }
            }
        }
    }
}

android {
    namespace = "io.github.manum45.openaa"
    compileSdk = 36 // Simplified version declaration


    defaultConfig {
        applicationId = "io.github.manum45.openaa"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    sourceSets {
        getByName("main") {
            proto {
                srcDir("src/main/proto")
            }
        }
    }
}
