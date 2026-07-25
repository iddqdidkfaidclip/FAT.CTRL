package vc.fatfukkers

/** Версия бота — инкрементируй при каждом релизе, чтобы в описании было видно обновление. */
object BotVersion {
    const val VERSION = "1.0.1"

    fun description(): String = """
Привет! Я твоя тренер 🩷 Слежу за весом, даю задания и отвечаю на вопросы.

Версия: $VERSION
""".trimIndent()

    fun shortDescription(): String =
        "Тренер · вес, задания, ИИ · v$VERSION"
}
