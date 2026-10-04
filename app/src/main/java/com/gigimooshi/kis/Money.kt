package com.gigimooshi.kis

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.abs

/** All money is stored as Long agorot (1/100 shekel) to avoid float drift. */
object Money {
    /** compact = drop ".00" on whole amounts (₪70 instead of ₪70.00). */
    fun fmt(agorot: Long, showPlus: Boolean = false, compact: Boolean = false): String {
        val sign = when {
            agorot < 0 -> "−"
            showPlus && agorot > 0 -> "+"
            else -> ""
        }
        val a = abs(agorot)
        return if (compact && a % 100 == 0L) {
            String.format(Locale.US, "%s₪%,d", sign, a / 100)
        } else {
            String.format(Locale.US, "%s₪%,d.%02d", sign, a / 100, a % 100)
        }
    }

    /** "70.00" — for prefilling input fields. */
    fun plain(agorot: Long): String = BigDecimal.valueOf(agorot, 2).toPlainString()

    /** Parses user input like "70", "12.5", "12,50", "-30". */
    fun parseInput(s: String): Long? {
        var t = s.trim().replace('−', '-').replace("₪", "").replace(" ", "")
        if (t.isEmpty()) return null
        t = if (t.contains('.')) t.replace(",", "") else t.replace(',', '.')
        val v = t.toBigDecimalOrNull() ?: return null
        return v.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
    }
}
