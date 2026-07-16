plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.kairuntime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.kairuntime"
        minSdk = 26
        targetSdk = 35
        versionCode = 11
        versionName = "0.3.5"

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }

        testInstrumentationRunner = "android.test.InstrumentationTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core:1.15.0") {
        version {
            strictly("1.15.0")
        }
    }
    implementation("org.mozilla.geckoview:geckoview:150.0.20260511200624")
    testImplementation("junit:junit:4.13.2")
}
