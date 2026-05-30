plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
}

group = "top.colter.dynamic"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
    maven(url = "https://maven.nova-committee.cn/releases/")
}

dependencies {
    val log4jVersion = "2.25.4"

    implementation("top.colter.dynamic:dynamic-bot-core:0.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    implementation("cn.evole.onebot:OneBot-Client:0.4.3")
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("org.apache.logging.log4j:log4j-api:$log4jVersion")
    implementation("com.google.code.gson:gson:2.8.9")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.20.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.16")
    testRuntimeOnly("org.apache.logging.log4j:log4j-to-slf4j:$log4jVersion")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds an executable fat jar with runtime dependencies."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.exists() && !it.isHostProvidedByDynamicBot() }.map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    })
}

fun File.isHostProvidedByDynamicBot(): Boolean {
    val normalizedPath = path.replace('\\', '/')
    return normalizedPath.contains("/dynamic-bot-core/build/") ||
        name.startsWith("dynamic-bot-core-") ||
        name.startsWith("kotlin-logging-jvm-") ||
        name.startsWith("log4j-api-") ||
        name.startsWith("log4j-core-") ||
        name.startsWith("log4j-slf4j") ||
        name.startsWith("log4j-to-slf4j-") ||
        name.startsWith("logback-") ||
        name.startsWith("jul-to-slf4j-") ||
        name.startsWith("slf4j-")
}

kotlin {
    jvmToolchain(21)
}
