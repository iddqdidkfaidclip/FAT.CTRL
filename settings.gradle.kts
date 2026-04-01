rootProject.name = "FATCTRLBOT"
pluginManagement {
    repositories {
        // Основные репозитории для загрузки плагинов
        mavenCentral()           // Maven Central для библиотек и плагинов
        gradlePluginPortal()     // Gradle Plugin Portal для плагинов Gradle
        google()                 // Google репозиторий (если требуется Android или другие плагины)
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