package ir.factory.entryexit.util

private val PERSIAN_DIGITS = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

/** Converts a non-negative integer to a zero-padded Persian-numeral string, e.g. 3 -> "۰۳". */
fun Int.toPersianDigits(minWidth: Int = 2): String {
    val raw = this.toString().padStart(minWidth, '0')
    val sb = StringBuilder(raw.length)
    for (c in raw) {
        sb.append(if (c.isDigit()) PERSIAN_DIGITS[c - '0'] else c)
    }
    return sb.toString()
}

/** Converts any western digits inside a string to Persian-Indic digits (for display only). */
fun String.toPersianDigitsInString(): String {
    val sb = StringBuilder(this.length)
    for (c in this) {
        sb.append(if (c.isDigit()) PERSIAN_DIGITS[c - '0'] else c)
    }
    return sb.toString()
}

/** Extracts just the digits from a string, normalizing any Persian-Indic digits to plain
 *  '0'-'9' along the way (e.g. "پلاک ۶۹۷۴۴" -> "69744"). Used to match an AI-read plate number
 *  against roster names regardless of digit script or surrounding text/prefix. */
fun String.extractDigits(): String {
    val sb = StringBuilder()
    for (c in this) {
        when {
            c in '0'..'9' -> sb.append(c)
            else -> {
                val idx = PERSIAN_DIGITS.indexOf(c)
                if (idx >= 0) sb.append('0' + idx)
            }
        }
    }
    return sb.toString()
}
