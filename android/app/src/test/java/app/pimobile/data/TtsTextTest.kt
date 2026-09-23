package app.pimobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsTextTest {

    // --- Plain Text ---

    @Test
    fun `plain text survives unchanged`() {
        assertEquals("Ciao Daniele.", TtsText.sanitize("Ciao Daniele."))
    }

    @Test
    fun `accented and unicode characters survive`() {
        val input = "È un'ottima opportunità per l'assistente vocale: caffè, città e novità."
        assertEquals(input, TtsText.sanitize(input))
    }

    // --- Fenced Code Blocks ---

    @Test
    fun `fenced code block with language is dropped`() {
        val md = "Ecco il codice:\n```kotlin\nfun main() = println(1)\n```\nFine."
        assertEquals("Ecco il codice: Fine.", TtsText.sanitize(md))
    }

    @Test
    fun `fenced code block without language is dropped`() {
        val md = "Intro\n```\nraw code line\n```\nOutro"
        assertEquals("Intro Outro", TtsText.sanitize(md))
    }

    @Test
    fun `tilde fences are dropped too`() {
        val md = "Prima\n~~~\nraw block\n~~~\nDopo"
        assertEquals("Prima Dopo", TtsText.sanitize(md))
    }

    @Test
    fun `multiple fenced code blocks are all dropped`() {
        val md = "Blocco 1:\n```python\nprint('a')\n```\nIn mezzo.\n```bash\nls -la\n```\nFine."
        assertEquals("Blocco 1: In mezzo. Fine.", TtsText.sanitize(md))
    }

    @Test
    fun `unclosed code fence at end of message is dropped`() {
        val md = "Generazione interrotta:\n```kotlin\nval x = 42"
        assertEquals("Generazione interrotta:", TtsText.sanitize(md))
    }

    @Test
    fun `empty result for code-only messages`() {
        assertEquals("", TtsText.sanitize("```\nonly code\n```"))
    }

    // --- Inline Code ---

    @Test
    fun `inline code keeps its text without backticks`() {
        assertEquals("Usa npm run dev qui", TtsText.sanitize("Usa `npm run dev` qui"))
    }

    @Test
    fun `inline code with complex command is preserved`() {
        assertEquals(
            "Esegui git commit -m 'feat: audio'",
            TtsText.sanitize("Esegui `git commit -m 'feat: audio'`"),
        )
    }

    // --- Identifiers and Snake_Case ---

    @Test
    fun `snake_case identifiers are preserved`() {
        val input = "Modifica user_id e data_store_test per la chiamata get_subagent_result()."
        assertEquals(input, TtsText.sanitize(input))
    }

    @Test
    fun `numeric literals with underscores are preserved`() {
        assertEquals("Il valore è 10_000_000 millisecondi.", TtsText.sanitize("Il valore è 10_000_000 millisecondi."))
    }

    @Test
    fun `uppercase constants with underscores are preserved`() {
        assertEquals("Usa DEFAULT_TTS_URL qui.", TtsText.sanitize("Usa DEFAULT_TTS_URL qui."))
    }

    @Test
    fun `python dunder in inline code is preserved`() {
        assertEquals("Il file __init__.py serve per il modulo.", TtsText.sanitize("Il file `__init__.py` serve per il modulo."))
    }

    // --- Math and Operators ---

    @Test
    fun `multiplication asterisks are preserved`() {
        assertEquals("Il calcolo è 2 * 3 = 6.", TtsText.sanitize("Il calcolo è 2 * 3 = 6."))
    }

    @Test
    fun `subtraction and negative numbers are preserved`() {
        assertEquals("Risultato: 10 - 4 = 6, con temperatura -5 gradi.", TtsText.sanitize("Risultato: 10 - 4 = 6, con temperatura -5 gradi."))
    }

    @Test
    fun `comparison operators are not swallowed as html`() {
        val input = "Se x < 10 && y > 20 allora procedi."
        assertEquals(input, TtsText.sanitize(input))
    }

    // --- Complex Identifiers and URLs ---

    @Test
    fun `raw url with query params and underscores is preserved`() {
        val input = "Endpoint: https://example.com/api/v1?user_id=42&session_token=abc_xyz"
        assertEquals(input, TtsText.sanitize(input))
    }

    @Test
    fun `email address with underscore is preserved`() {
        val input = "Contatta developer_team@pi.dev per info."
        assertEquals(input, TtsText.sanitize(input))
    }

    @Test
    fun `method invocation on snake_case variable is preserved`() {
        val input = "Chiama user_account.refresh_token() all'avvio."
        assertEquals(input, TtsText.sanitize(input))
    }

    // --- Links and Images ---

    @Test
    fun `inline links keep only their label`() {
        assertEquals("Leggi la documentazione qui.", TtsText.sanitize("Leggi la [documentazione](https://pi.dev) qui."))
    }

    @Test
    fun `reference style links keep their label`() {
        assertEquals("Consulta la guida ufficiale.", TtsText.sanitize("Consulta la [guida ufficiale][guide-ref]."))
    }

    @Test
    fun `images are dropped completely`() {
        assertEquals("Prima dopo.", TtsText.sanitize("Prima ![screenshot di esempio](img.png) dopo."))
    }

    // --- Headings ---

    @Test
    fun `all heading levels lose their hash markers`() {
        val md = """
            # Titolo 1
            ## Titolo 2
            ### Titolo 3
            #### Titolo 4
            ##### Titolo 5
            ###### Titolo 6
            Contenuto.
        """.trimIndent()
        val expected = "Titolo 1\nTitolo 2\nTitolo 3\nTitolo 4\nTitolo 5\nTitolo 6\nContenuto."
        assertEquals(expected, TtsText.sanitize(md))
    }

    // --- Blockquotes ---

    @Test
    fun `blockquotes lose their markers`() {
        val md = "> Questa è una citazione.\n> Seconda riga della citazione."
        assertEquals("Questa è una citazione.\nSeconda riga della citazione.", TtsText.sanitize(md))
    }

    // --- Lists ---

    @Test
    fun `dash bullet list markers are stripped`() {
        val md = "- primo elemento\n- secondo elemento\n- terzo elemento"
        assertEquals("primo elemento\nsecondo elemento\nterzo elemento", TtsText.sanitize(md))
    }

    @Test
    fun `asterisk bullet list markers are stripped`() {
        val md = "* elemento A\n* elemento B"
        assertEquals("elemento A\nelemento B", TtsText.sanitize(md))
    }

    @Test
    fun `plus bullet list markers are stripped`() {
        val md = "+ step alfa\n+ step beta"
        assertEquals("step alfa\nstep beta", TtsText.sanitize(md))
    }

    @Test
    fun `numbered list markers are retained for speaking`() {
        val md = "1. primo passo\n2. secondo passo\n3. terzo passo"
        assertEquals(md, TtsText.sanitize(md))
    }

    @Test
    fun `mixed numbered and bullet lists behave correctly`() {
        val md = "1. punto uno\n   - sotto punto\n2. punto due"
        assertEquals("1. punto uno\nsotto punto\n2. punto due", TtsText.sanitize(md))
    }

    // --- Horizontal Rules ---

    @Test
    fun `horizontal rules with dashes, asterisks and underscores are stripped`() {
        val md = "Sezione 1\n---\nSezione 2\n***\nSezione 3\n___\nFine."
        assertEquals("Sezione 1\nSezione 2\nSezione 3\nFine.", TtsText.sanitize(md))
    }

    @Test
    fun `spaced horizontal rules are stripped`() {
        val md = "Inizio\n- - -\nFine"
        assertEquals("Inizio\nFine", TtsText.sanitize(md))
    }

    // --- Markdown Tables ---

    @Test
    fun `markdown table delimiters and pipes are cleaned`() {
        val md = """
            | Nome | Tipo |
            | :--- | :--- |
            | Daniele | Dev |
        """.trimIndent()
        assertEquals("Nome Tipo\nDaniele Dev", TtsText.sanitize(md))
    }

    // --- HTML Tags ---

    @Test
    fun `html tags are stripped`() {
        val md = "Testo con <br> a capo e <b>grassetto</b> o <span class=\"x\">span</span>."
        assertEquals("Testo con\na capo e grassetto o span.", TtsText.sanitize(md))
    }

    // --- Bold and Italic ---

    @Test
    fun `bold with double asterisks loses markers`() {
        assertEquals("Questo è molto importante.", TtsText.sanitize("Questo è **molto importante**."))
    }

    @Test
    fun `bold with double underscores loses markers`() {
        assertEquals("Questo è molto importante.", TtsText.sanitize("Questo è __molto importante__."))
    }

    @Test
    fun `italic with single asterisk loses markers`() {
        assertEquals("Questo è un testo corsivo.", TtsText.sanitize("Questo è un *testo corsivo*."))
    }

    @Test
    fun `italic with single underscore loses markers`() {
        assertEquals("Questo è un testo corsivo.", TtsText.sanitize("Questo è un _testo corsivo_."))
    }

    @Test
    fun `bold and italic with punctuation attached lose markers`() {
        val md = "Ecco **fatto**, e *bene*! Oppure _questo_?"
        assertEquals("Ecco fatto, e bene! Oppure questo?", TtsText.sanitize(md))
    }

    @Test
    fun `strikethrough markers are stripped`() {
        assertEquals("Questo testo è obsoleto deprecato.", TtsText.sanitize("Questo testo è ~~obsoleto~~ deprecato."))
    }

    @Test
    fun `escaped markdown characters are unescaped`() {
        assertEquals("Testo con *asterisco* e _underscore_ letterali.", TtsText.sanitize("Testo con \\*asterisco\\* e \\_underscore\\_ letterali."))
    }

    @Test
    fun `emoji with markdown formatting are preserved`() {
        val md = "🚀 **Grande successo**! Il server è _online_ 🎉."
        assertEquals("🚀 Grande successo! Il server è online 🎉.", TtsText.sanitize(md))
    }

    // --- Full Document Integration Test ---

    @Test
    fun `comprehensive realistic assistant response is sanitized cleanly`() {
        val md = """
            # Aggiornamento Servizio
            
            Abbiamo completato il refactor di `TtsPlayer.kt`. Ecco i punti principali:
            
            - **Streaming puro**: rimosso `cacheDir/tts` e SHA-1.
            - **Lifecycle sicuro**: nessun arresto prematuro del servizio.
            - **Performance**: 2 * 3 = 6 volte più veloce all'avvio.
            
            > Nota: verificare sempre `user_id` e `session_token` prima dell'invio.
            
            ```kotlin
            fun unusedCode() {
                println("non deve essere letto ad alta voce")
            }
            ```
            
            Per dettagli, consulta la [documentazione ufficiale](https://pi.dev) oppure contatta dev_team@pi.dev.
        """.trimIndent()

        val expected = """
            Aggiornamento Servizio
            Abbiamo completato il refactor di TtsPlayer.kt. Ecco i punti principali:
            Streaming puro: rimosso cacheDir/tts e SHA-1.
            Lifecycle sicuro: nessun arresto prematuro del servizio.
            Performance: 2 * 3 = 6 volte più veloce all'avvio.
            Nota: verificare sempre user_id e session_token prima dell'invio.
            Per dettagli, consulta la documentazione ufficiale oppure contatta dev_team@pi.dev.
        """.trimIndent()

        assertEquals(expected, TtsText.sanitize(md))
    }

    // --- Whitespace Collapse ---

    @Test
    fun `redundant whitespace and empty lines are collapsed`() {
        assertEquals("a b\nc", TtsText.sanitize("a    b\n\n\n\nc"))
    }

    @Test
    fun `leading and trailing whitespace is trimmed`() {
        assertEquals("ciao", TtsText.sanitize("   \n\n ciao \n\n  "))
    }

    @Test
    fun `empty and blank strings return empty`() {
        assertEquals("", TtsText.sanitize(""))
        assertEquals("", TtsText.sanitize("   \n\t  \n  "))
    }
}
