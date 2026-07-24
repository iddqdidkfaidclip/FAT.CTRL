package vc.fatfukkers.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiChatServiceTest {
    @Test
    fun `extracts assistant content from openai-style response`() {
        val json = """
            {
              "id": "chatcmpl-1",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Привет, котик 🩷"
                  }
                }
              ]
            }
        """.trimIndent()
        assertEquals("Привет, котик 🩷", AiChatService.extractAssistantContent(json))
    }

    @Test
    fun `returns null on empty choices`() {
        assertNull(AiChatService.extractAssistantContent("""{"choices":[]}"""))
    }

    @Test
    fun `returns null on invalid json`() {
        assertNull(AiChatService.extractAssistantContent("not-json"))
    }
}
