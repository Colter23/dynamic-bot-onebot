plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
}

apply(from = "gradle/dynamic-plugin-fatjar.gradle.kts")

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://maven.nova-committee.cn/releases/")
}

configurations.named("testRuntimeClasspath") {
    resolutionStrategy.force("org.slf4j:slf4j-api:2.0.13")
}

dependencies {
    val coreVersion = "0.0.6"
    val kotlinLoggingVersion = "7.0.0"
    val log4jVersion = "2.25.4"

    compileOnly("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    implementation("cn.evole.onebot:OneBot-Client:0.4.3")
    compileOnly("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    compileOnly("org.apache.logging.log4j:log4j-api:$log4jVersion")
    implementation("com.google.code.gson:gson:2.8.9")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    testImplementation(kotlin("test"))
    testImplementation("top.colter.dynamic:dynamic-bot-core:$coreVersion")
    testImplementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    testImplementation("org.apache.logging.log4j:log4j-api:$log4jVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testRuntimeOnly("org.slf4j:slf4j-api:2.0.13")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.16")
    testRuntimeOnly("org.apache.logging.log4j:log4j-to-slf4j:$log4jVersion")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
