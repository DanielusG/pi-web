package app.pimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The attachment prefix (pi-web lib/attachment-prefix.ts): same text, same split, both sides. */
class AttachmentPrefixTest {
    private val dir = "/home/u/.pi/agent/pi-web-attachments/0b7e6f3c-1d2a-4c55-9e1f-2a3b4c5d6e7f/"
    private val files = listOf(
        AttachmentRef("${dir}a1b2c3_text1.txt", "text1.txt"),
        AttachmentRef("${dir}9e8d7c_my_document_1_.pdf", "my document (1).pdf"),
    )

    // The exact text lib/attachment-prefix.test.mjs expects too.
    private val expected = listOf(
        "Attachments from the user (you should probably read them; Pi saved them in $dir):",
        "- ${dir}a1b2c3_text1.txt (original name: text1.txt)",
        "- ${dir}9e8d7c_my_document_1_.pdf (original name: my document (1).pdf)",
        "",
        "---",
        "",
        "Summarize these",
    ).joinToString("\n")

    @Test
    fun `the prefix lists each file with its original name, then the typed text`() {
        assertEquals(expected, AttachmentPrefix.wrap(dir, files, "Summarize these"))
    }

    @Test
    fun `files alone send the list without a separator`() {
        val text = AttachmentPrefix.wrap(dir, files, "")
        assertEquals(AttachmentPrefix.format(dir, files), text)
        assertEquals(AttachmentPrefix.Split(dir, files, ""), AttachmentPrefix.split(text))
    }

    @Test
    fun `no attachments leave the text alone`() {
        assertEquals("hello", AttachmentPrefix.wrap(dir, emptyList(), "hello"))
    }

    @Test
    fun `split gives back the directory, the files and a multi-line body`() {
        val body = "line one\n\n---\n\nline two"
        assertEquals(AttachmentPrefix.Split(dir, files, body), AttachmentPrefix.split(AttachmentPrefix.wrap(dir, files, body)))
    }

    @Test
    fun `a newline in a device file name stays on its line`() {
        val text = AttachmentPrefix.wrap(dir, listOf(AttachmentRef("${dir}a1b2c3_a_b.txt", "a\nb.txt")), "x")
        assertEquals(listOf(AttachmentRef("${dir}a1b2c3_a_b.txt", "a b.txt")), AttachmentPrefix.split(text)?.attachments)
    }

    @Test
    fun `a list shows the typed text, or the names for files alone`() {
        assertEquals("Summarize these", AttachmentPrefix.display(expected))
        assertEquals("text1.txt, my document (1).pdf", AttachmentPrefix.display(AttachmentPrefix.format(dir, files)))
        assertEquals("plain message", AttachmentPrefix.display("plain message"))
    }

    @Test
    fun `a lead an extension put before the prefix is kept apart`() {
        // e.g. a timestamp extension's input handler: "⏱ <time>" on its own line, or on the header's.
        for (lead in listOf("⏱ 2026-11-09 20:45:12\n", "[20:45] ")) {
            val split = AttachmentPrefix.split(lead + expected)
            assertEquals(AttachmentPrefix.Split(dir, files, "Summarize these", lead), split)
            assertEquals("${lead}Summarize these", split?.text)
            assertEquals("${lead}Summarize these", AttachmentPrefix.display(lead + expected))
            val alone = lead + AttachmentPrefix.format(dir, files)
            assertEquals(lead.trimEnd(), AttachmentPrefix.split(alone)?.text)
            assertEquals("${lead}text1.txt, my document (1).pdf", AttachmentPrefix.display(alone))
        }
    }

    @Test
    fun `text that only looks like a prefix is not split`() {
        assertNull(AttachmentPrefix.split("Attachments from the user are below"))
        assertNull(AttachmentPrefix.split(expected.replace("\n\n---\n\n", "\n---\n")))
        val header = expected.lineSequence().first()
        assertNull(AttachmentPrefix.split("$header\nno list here"))
        assertNull(AttachmentPrefix.split("$header\n- missing the original name"))
    }
}
