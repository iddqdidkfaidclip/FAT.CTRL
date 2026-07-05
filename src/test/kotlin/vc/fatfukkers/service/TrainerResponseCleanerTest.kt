package vc.fatfukkers.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TrainerResponseCleanerTest {
  @Test
  fun `strips stray delimiters and keeps last answer segment`() {
    val raw =
      "Ахха, котик, я не заебала! 😳 ||| Никуда не уходила, просто занималась университетскими делами. 🙈 |||"

    val cleaned = TrainerResponseCleaner.clean(raw)
    assertFalse(cleaned.contains("|||"))
    assertEquals(
      "Никуда не уходила, просто занималась университетскими делами. 🙈",
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
