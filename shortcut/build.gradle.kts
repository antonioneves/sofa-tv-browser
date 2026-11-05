plugins {
    id("com.android.application")
}

/**
 * The template behind "Add to TV home screen". Sofa copies this APK, patches the name, address and banner into it on
 * the TV and installs the copy, so each saved page gets its own tile in the launcher. Never installed as built here.
 */
android {
    namespace = "app.sofabrowser.shortcut"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "app.sofabrowser.shortcut"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1"
    }
    buildTypes {
        release {
            // Drops the Kotlin runtime the build adds, so each tile's APK is a few kilobytes.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}
