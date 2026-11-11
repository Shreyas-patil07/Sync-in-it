// SPDX-License-Identifier: Apache-2.0
import org.gradle.api.GradleException
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

val releaseKeystorePath = providers.gradleProperty("releaseKeystorePath").orNull
val releaseStorePassword = providers.gradleProperty("releaseStorePassword").orNull
val releaseKeyAlias = providers.gradleProperty("releaseKeyAlias").orNull
val releaseKeyPassword = providers.gradleProperty("releaseKeyPassword").orNull
val releaseSigningEnabled = listOf(
    releaseKeystorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() } && file(releaseKeystorePath!!).isFile

val requestedVersionCode = providers.gradleProperty("syncinitVersionCode")
    .orNull
    ?.toIntOrNull()
    ?: 1

val requestedVersionName = providers.gradleProperty("syncinitVersionName")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "0.9.0"

require(requestedVersionCode in 1..2_100_000_000) {
    "syncinitVersionCode must be between 1 and 2100000000"
}

val verifyStableReleaseSigning = tasks.register("verifyStableReleaseSigning") {
    doLast {
        if (!releaseSigningEnabled) {
            throw GradleException(
                "Stable release builds require a dedicated production keystore. " +
                    "Set releaseKeystorePath, releaseStorePassword, releaseKeyAlias and releaseKeyPassword."
            )
        }
    }
}

android {
    buildFeatures {
        buildConfig = true
    }

    sourceSets.getByName("main").res.srcDir(generatedBrandResources)
    namespace = "com.syncinit.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.syncinit.app"
        minSdk = 26
        targetSdk = 36
        versionCode = requestedVersionCode
        versionName = requestedVersionName
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
        if (releaseSigningEnabled) {
            create("production") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
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
            // A stable build must never fall back to the preview signing certificate.
            if (releaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("production")
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

tasks.configureEach {
    if (name == "preBuild") dependsOn(generateBrandResources)
    if (name == "preReleaseBuild") dependsOn(verifyStableReleaseSigning)
}

dependencies {
    implementation(project(":backend"))
    implementation("com.google.zxing:core:3.5.4")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("io.livekit:livekit-android:2.30.0")
}
