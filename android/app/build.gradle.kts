plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val previewKeystorePath = providers.gradleProperty("previewKeystorePath").orNull
val previewStorePassword = providers.gradleProperty("previewStorePassword").orNull
val previewKeyAlias = providers.gradleProperty("previewKeyAlias").orNull
val previewKeyPassword = providers.gradleProperty("previewKeyPassword").orNull
val previewSigningEnabled = listOf(
    previewKeystorePath,
    previewStorePassword,
    previewKeyAlias,
    previewKeyPassword
).all { !it.isNullOrBlank() } && file(previewKeystorePath!!).isFile

val requestedVersionCode = providers.gradleProperty("syncinitVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: 1

require(requestedVersionCode in 1..2_100_000_000) {
    "syncinitVersionCode must be between 1 and 2100000000"
}

android {
    namespace = "com.syncinit.app"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.syncinit.app"
        minSdk = 26
        targetSdk = 36
        versionCode = requestedVersionCode
        versionName = "0.5.6-preview"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        if (previewSigningEnabled) {
            create("preview") {
                storeFile = file(previewKeystorePath!!)
                storePassword = previewStorePassword
                keyAlias = previewKeyAlias
                keyPassword = previewKeyPassword
                storeType = "JKS"
            }
        }
    }

    buildTypes {
        debug {
            // Keep this suffix: it preserves the package ID used by existing preview installs.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (previewSigningEnabled) {
                signingConfig = signingConfigs.getByName("preview")
            }
        }
        release {
            isMinifyEnabled = false
            if (previewSigningEnabled) {
                signingConfig = signingConfigs.getByName("preview")
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(project(":transport"))
}
