plugins {
    kotlin("jvm") version "2.4.10"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

tasks.register<JavaExec>("transportTest") {
    group = "verification"
    description = "Runs loopback TCP and UDP transport integration tests."
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.named("test").get().runtimeClasspath
    mainClass.set("com.syncinit.transport.TransportSocketTestsKt")
}

tasks.named("check") {
    dependsOn("transportTest")
}
