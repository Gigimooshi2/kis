package com.gigimooshi.kis

import java.math.RoundingMode

/**
 * Pulls a shekel amount out of notification text.
 * Handles "₪45.90", "45.90 ₪", "ILS 45.90", "45.90 ש״ח", thousands separators,
 * comma decimals, and the invisible RTL/LTR marks Hebrew notifications are full of.
 */
object AmountParser {
    enum class Kind { EXPENSE, REFUND, IGNORE }
    data class Result(val agorot: Long, val kind: Kind)

    private val BIDI = Regex("[\u200E\u200F\u202A-\u202E\u2066-\u2069\u00A0\u202F]")
    private const val NUM = "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+[.,]\\d{1,2}(?!\\d)|\\d+)"
    private const val CUR_BEFORE = "(?:₪|ILS|NIS)"
    private const val CUR_AFTER = "(?:₪|ILS|NIS|ש\"ח|ש״ח|שח|שקלים|שקל)"
    private val PREFIX = Regex("$CUR_BEFORE\\s*-?\\s*$NUM", RegexOption.IGNORE_CASE)
    private val SUFFIX = Regex("$NUM\\s*$CUR_AFTER", RegexOption.IGNORE_CASE)

    private val REFUND_WORDS = listOf("refund", "החזר", "זיכוי", "הוחזר")
    private val IGNORE_WORDS = listOf(
        "declined", "failed", "couldn't", "could not", "unsuccessful",
        "נדחה", "נדחתה", "נכשל", "נכשלה", "לא הצליח", "לא בוצע",
    )

    fun parse(text: String): Result? {
        val clean = BIDI.replace(text, " ")
        val m = PREFIX.find(clean) ?: SUFFIX.find(clean) ?: return null
        val agorot = toAgorot(m.groupValues[1]) ?: return null
        if (agorot <= 0) return null
        val lower = clean.lowercase()
        val kind = when {
            IGNORE_WORDS.any { lower.contains(it) } -> Kind.IGNORE
            REFUND_WORDS.any { lower.contains(it) } -> Kind.REFUND
            else -> Kind.EXPENSE
        }
        return Result(agorot, kind)
    }

    private fun toAgorot(raw: String): Long? {
        val normalized = when {
            raw.contains('.') -> raw.replace(",", "")
            // "1,234" = thousands, "12,50" = decimal comma
            raw.contains(',') && raw.substringAfterLast(',').length == 3 -> raw.replace(",", "")
            else -> raw.replace(',', '.')
        }
        val v = normalized.toBigDecimalOrNull() ?: return null
        return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }
}
