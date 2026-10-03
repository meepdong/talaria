package io.github.meepdong.talaria.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopSpeechTest {
    @Test
    fun voskResultsAreRead() {
        assertEquals("hello there", VoskInput.field("{\n  \"partial\" : \"hello there\"\n}", "partial"))
        assertEquals("say \"hi\"", VoskInput.field("""{"text" : "say \"hi\""}""", "text"))
        assertEquals("", VoskInput.field("""{"text" : ""}""", "text"))
        assertNull(VoskInput.field("""{"partial" : "x"}""", "text"))
    }
}
