package com.jarvis.assistant.core

/** Voice/text commands that switch between phone mode and PC mode ("PC mode on", "phone mode", "पीसी मोड"). */
object ModeCommand {
    private val pcWords = Regex("""\b(pc|p c|computer|laptop|desktop)\b|पीसी|कंप्यूटर|कॉम्प्युटर|संगणक""", RegexOption.IGNORE_CASE)
    private val phoneWords = Regex("""\b(phone|mobile|android)\b|फोन|मोबाईल|मोबाइल""", RegexOption.IGNORE_CASE)
    private val modeWords = Regex("""\bmode\b|मोड|\bswitch to\b|स्विच""", RegexOption.IGNORE_CASE)
    private val offWords = Regex("""\b(off|disable|stop|exit)\b|बंद|बन्द|band""", RegexOption.IGNORE_CASE)

    /** true = switch to PC mode, false = switch to phone mode, null = not a mode command. */
    fun parse(text: String): Boolean? {
        if (text.length > 60 || !modeWords.containsMatchIn(text)) return null
        val pc = pcWords.containsMatchIn(text)
        val phone = phoneWords.containsMatchIn(text)
        return when {
            pc && !phone -> !offWords.containsMatchIn(text) // "PC mode off" -> back to phone
            phone && !pc -> if (offWords.containsMatchIn(text)) null else false
            else -> null
        }
    }
}
