package io.github.meepdong.talaria.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceTest {
    @Test
    fun markdownIsReadAsPlainSpeech() {
        val reply = """
            ## Your **statement**

            - Groceries: *£120*
            - See [the bank](https://bank.example/x) or https://other.example

            | Category | Total |
            |---|---|
            | Rent | 900 |

            ```python
            print("hi")
            ```
            > Done.
        """.trimIndent()
        assertEquals(
            "Your statement\nGroceries: £120\nSee the bank or a link\nCategory , Total\nRent , 900\n(code)\nDone.",
            speakable(reply),
        )
    }

    @Test
    fun longRepliesAreCutAtAWord() {
        val said = speakable("word ".repeat(100), maxChars = 42)
        assertTrue(said.endsWith("… The rest is on screen."))
        assertTrue(said.length < 42 + 30)
    }

    @Test
    fun fileBackedPrefsSurviveARestart() {
        val file = kotlin.io.path.createTempDirectory("talaria-prefs").resolve("settings.properties").toFile()
        Prefs.FileBacked(file).set("voice.read_aloud", true)
        assertTrue(Prefs.FileBacked(file).get("voice.read_aloud", false))
        assertEquals(false, Prefs.FileBacked(file).get("voice.auto_send", false))
    }
}
