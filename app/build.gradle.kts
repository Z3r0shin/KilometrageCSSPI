plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.transportlogger"
    compileSdk = 35

    signingConfigs {
        // Shared debug key, so every machine that builds this app produces the
        // same signature. Without it each machine signs with its own
        // ~/.android/debug.keystore, and installing a build from one over a
        // build from the other fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE --
        // which can only be resolved by uninstalling, taking the trips and the
        // configured zones with it. The file is gitignored (the repo is public)
        // and copied to each machine by hand; when it is missing, the machine's
        // own debug key is used. Debug only; never sign a release with this.
        val sharedKey = rootProject.file("debug.keystore")
        if (sharedKey.exists()) {
            getByName("debug") {
                storeFile = sharedKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.transportlogger"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // In-app P.O. scanner: CameraX frames decoded by ZXing (see ScanActivity).
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}