plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.shilapi.airplaysink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shilapi.airplaysink"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    // The head unit is a plain Android device: no automotive library, no CarPlay host.
    // Signing for a car install is done locally with the release keystore below.
    // When ANDROID_KEYSTORE_PATH is unset (or points nowhere) the release build is left
    // unsigned: a missing storeFile would otherwise fail validateSigningRelease.
    val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    val keystoreFile = keystorePath?.let { file(it) }?.takeIf { it.isFile }
    if (keystorePath != null && keystoreFile == null) {
        logger.warn("ANDROID_KEYSTORE_PATH=$keystorePath not found; release APK will be unsigned")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
                keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
                keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // null = unsigned, lets assembleRelease run without a keystore
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
}
