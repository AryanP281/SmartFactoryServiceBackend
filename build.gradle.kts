import sun.jvmstat.monitor.MonitoredVmUtil.mainClass

plugins {
    application
    kotlin("jvm") version "2.3.10"
    id("application")
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("org.apache.fory:fory-core:0.15.0")
    implementation("org.apache.fory:fory-kotlin:0.15.0")

    implementation("com.microsoft.onnxruntime:onnxruntime:latest.release")

    implementation("io.dapr:dapr-sdk:1.18.0")

    implementation("org.slf4j:slf4j-api:2.0.16")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.16")

    implementation(files("libs/api.jar"))
}

kotlin {
    jvmToolchain(25)
}

application {
    mainClass.set("org.example.MainKt")
}

sourceSets {
    main {
        java { srcDir("build/generated/fory/foryGenJava/java")}
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("generateForyTypes", Exec::class.java) {
    commandLine(
        ".venvs/bin/foryc",
        "--lang",
        "java",
        "-o",
        "build/generated/fory/foryGenJava",
        "fdl/Event.fdl",
    )
}