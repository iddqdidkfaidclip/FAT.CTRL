package vc.fatfukkers.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrainerResponseCleanerTest {
  @Test
  fun `strips reasoning before answer delimiter`() {
    val raw = """
      Хорошо, мне нужно ответить на вопрос про ГТА 6. Сначала подумаю, как объяснить.
      Проверю, что не переборщу с деталями. Может, 8 предложений хватит.
      |||
      ГТА 6 — это шестая часть серии от Rockstar Games, вышедшая в 2024 году. Тут ты в Лос-Анжелесе смотришь на мир через призму криминала.

      Хз, котик, если твоя игра не настроена — отстань и лови таблетки. 😊
    """.trimIndent()

    val cleaned = TrainerResponseCleaner.clean(raw)
    assertFalse(cleaned.contains("Хорошо, мне нужно"))
    assertFalse(cleaned.contains("|||"))
    assertEquals(
      """
      ГТА 6 — это шестая часть серии от Rockstar Games, вышедшая в 2024 году. Тут ты в Лос-Анжелесе смотришь на мир через призму криминала.

      Хз, котик, если твоя игра не настроена — отстань и лови таблетки. 😊
      """.trimIndent(),
      cleaned,
    )
  }

  @Test
  fun `strips reasoning before think close tag`() {
    val thinkClose = "</" + "think>"
    val raw = """
      Надо проверить, не слишком ли много деталей.
      $thinkClose

      ГТА 6 — это шестая часть серии от Rockstar Games. В ней открытый мир и криминальные приключения. 😊
    """.trimIndent()

    val cleaned = TrainerResponseCleaner.clean(raw)
    assertFalse(cleaned.contains("Надо проверить"))
    assertEquals(
      "ГТА 6 — это шестая часть серии от Rockstar Games. В ней открытый мир и криминальные приключения. 😊",
      cleaned,
    )
  }

  @Test
  fun `removes trainer role prefix`() {
    assertEquals(
      "Привет, котик. 😊",
      TrainerResponseCleaner.clean("Тренер: Привет, котик. 😊"),
    )
  }
}
