plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.gigimooshi.kis"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gigimooshi.kis"
        minSdk = 26
        targetSdk = 34
        // CI run number, so every build installs as an update
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // Stable signing key from CI (see workflow), so updates install over the old version.
    // Falls back to the default throwaway debug key when building locally.
    signingConfigs {
        getByName("debug") {
            val ks = System.getenv("KIS_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
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
