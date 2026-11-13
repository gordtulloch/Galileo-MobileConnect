// quest - the Meta Quest edition (docs/quest/SDD.md). A NativeActivity subclass: the C++ layer owns OpenXR, the star field and
// the input; Kotlin owns the session, the dock, the panes and every decision, using :quest-logic and :app-ui.

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

repositories {
    google()
    mavenCentral()
}

android {
    namespace = "com.galileo.mobileconnect.quest"
    compileSdk = 35
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.gordtulloch.galileo.mobileconnect.quest"
        minSdk = 29 // Horizon OS is Android 12L-based; the spike ran on 29+
        targetSdk = 32
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-O2"
            }
        }
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so that adb and SideQuest can install it; a release key replaces this (MAPP-QST-210).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint { abortOnError = false }
}

dependencies {
    implementation(project(":app-ui"))
    implementation(project(":quest-logic"))
    implementation("org.khronos.openxr:openxr_loader_for_android:1.1.43")
}
