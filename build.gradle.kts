plugins {
    kotlin("jvm") version "2.2.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "vc.fatfukkers"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:10.0.0")

    // DB
    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")
    implementation("com.zaxxer:HikariCP:6.2.1")
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    // needed to resolve retrofit2.Response return type from telegram-bot sendPhoto
    implementation("com.squareup.retrofit2:retrofit:2.9.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // YouTube через InnerTube (NewPipe) — запасной путь, если yt-dlp блокирует VPS
    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "vc.fatfukkers.MainKt"
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    archiveBaseName.set("fatctrlbot")
    archiveClassifier.set("")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "vc.fatfukkers.MainKt"
    }
}

tasks.register("fatJar") {
    group = "build"
    description = "Собрать fat-jar для деплоя на сервер → build/libs/fatctrlbot.jar"
    dependsOn(tasks.named("shadowJar"))
}

tasks.register<JavaExec>("importTelegramJokes") {
    group = "tools"
    description = "Импортирует анекдоты из Telegram ChatExport (js/json)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("vc.fatfukkers.tools.TelegramJokesImporter")

    val sourcePath = (project.findProperty("source") as String?)?.trim().orEmpty()
    val outputPath = (project.findProperty("output") as String?)?.trim()
        ?: "src/main/resources/jokes.txt"
    if (sourcePath.isBlank()) {
        throw GradleException("Pass Telegram export path: -Psource='/path/to/ChatExport/js'")
    }
    args(sourcePath, outputPath)
}