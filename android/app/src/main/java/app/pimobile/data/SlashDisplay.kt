package app.pimobile.data

/**
 * Display-only restoration of the envelope pi's `_expandSkillCommand` emits
 * (web: lib/slash-display.ts). The expanded text stays the stored session input.
 */
object SlashDisplay {
    // Requires the opening envelope, the base-directory line, the final closing
    // tag and an optional two-newline args suffix, so ordinary text that merely
    // starts with a skill-looking tag is left alone. The greedy body makes the
    // last `</skill>` win when a skill body contains an example closing tag.
    private val SKILL_EXPANSION = Regex(
        "^<skill name=\"([^\"\\n]+)\" location=\"([^\"\\n]+)\">\\n" +
            "References are relative to [^\\n]+\\.\\n\\n([\\s\\S]*)\\n</skill>(?:\\n\\n([\\s\\S]+))?$",
    )

    /** `/skill:<name> [args]` for a complete skill expansion, else null. */
    fun skillExpansionToCommand(text: String): String? {
        val match = SKILL_EXPANSION.matchEntire(text) ?: return null
        val name = match.groupValues[1]
        val args = match.groups[4]?.value
        return if (args.isNullOrEmpty()) "/skill:$name" else "/skill:$name $args"
    }

    /** [text] as the user typed it: a skill expansion collapses back to its command. */
    fun display(text: String): String = skillExpansionToCommand(text) ?: text
}
