// SPDX-License-Identifier: Apache-2.0
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("com.google.code.gson:gson:2.15.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.1")
}

tasks.register<JavaExec>("backendTest") {
    group = "verification"
    description = "Runs backend REST and WebSocket client contract tests."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.named("test").get().runtimeClasspath
    mainClass.set("com.syncinit.backend.BackendClientTestsKt")
}

tasks.named("check") {
    dependsOn("backendTest")
}
