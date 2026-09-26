package app.pimobile.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LastLinesTest {

    @Test
    fun matchesLinesTakeLast() {
        val texts = listOf(
            "",
            "one line",
            "a\nb\nc",
            "a\n\n\nb\n",
            "\n\n",
            "crlf 1\r\ncrlf 2\r\ncrlf 3",
            "progress 10%\rprogress 50%\rprogress 100%\ndone",
            (1..500).joinToString("\n") { "line $it" },
            "trailing\r",
        )
        for (text in texts) {
            for (count in listOf(1, 2, 8, 20)) {
                assertEquals("$count of ${text.take(30)}", text.lines().takeLast(count).joinToString("\n"), lastLines(text, count))
            }
        }
    }
}
