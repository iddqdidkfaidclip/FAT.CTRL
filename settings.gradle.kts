rootProject.name = "FATCTRLBOT"
pluginManagement {
    repositories {
        // Основные репозитории для загрузки плагинов
        mavenCentral()           // Maven Central для библиотек и плагинов
        gradlePluginPortal()     // Gradle Plugin Portal для плагинов Gradle
        google()                 // Google репозиторий (если требуется Android или другие плагины)
    }

}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        // Репозитории для зависимостей проекта
        mavenCentral()
        maven("https://jitpack.io")
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        // Репозитории для зависимостей проекта
        mavenCentral()
        maven("https://jitpack.io")
        google()
    }
}