plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.kairuntime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.kairuntime"
        minSdk = 23
        targetSdk = 35
        versionCode = 5
        versionName = "0.2.1"

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

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:128.0.20240725162350")
    testImplementation("junit:junit:4.13.2")
}
