// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.tasks.Copy

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedBrandResources = layout.buildDirectory.dir("generated/res/brand/main")
val generateBrandResources = tasks.register<Copy>("generateBrandResources") {
    from(rootProject.file("../frontend/public/images/sync_in_it.png"))
    into(generatedBrandResources.map { it.dir("drawable-nodpi") })
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

val requestedVersionName = providers.gradleProperty("syncinitVersionName")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "0.6.6"

val rtpAudioEnabled = providers.gradleProperty("syncinitRtpAudioEnabled")
    .orNull
    ?.toBooleanStrictOrNull()
    ?: false

require(requestedVersionCode in 1..2_100_000_000) {
    "syncinitVersionCode must be between 1 and 2100000000"
}

android {
    buildFeatures {
        buildConfig = true
    }

    sourceSets.getByName("main").res.srcDir(generatedBrandResources)
    namespace = "com.syncinit.app"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.syncinit.app"
        minSdk = 26
        targetSdk = 36
        versionCode = requestedVersionCode
        versionName = requestedVersionName
        buildConfigField("boolean", "RTP_AUDIO_ENABLED", rtpAudioEnabled.toString())

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

tasks.configureEach {
    if (name == "preBuild") dependsOn(generateBrandResources)
}

dependencies {
    implementation(project(":transport"))
    implementation(project(":backend"))
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("io.livekit:livekit-android:2.30.0")
}
