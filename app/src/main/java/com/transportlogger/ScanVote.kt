package com.transportlogger

/**
 * Decides when repeated reads of a P.O. label can be trusted.
 *
 * The labels are Code 39, which carries no check digit: a decoder can return a
 * shortened or altered value from a bad frame and nothing in the barcode says
 * so. A single read therefore proves nothing. A P.O. is always 9 characters,
 * so anything else is discarded, and a value is only accepted once it has been
 * read [needed] times and clearly more often than any other candidate.
 */
class ScanVote(private val needed: Int = 3) {

    private val counts = HashMap<String, Int>()

    /** The candidate read most often so far, with its count. */
    var leader: Pair<String, Int>? = null
        private set

    /** Feed one decoded value. Returns the accepted code, or null while undecided. */
    fun offer(raw: String): String? {
        val code = raw.trim().uppercase()
        if (!SHAPE.matches(code)) return null

        val n = (counts[code] ?: 0) + 1
        counts[code] = n
        if (n >= (leader?.second ?: 0)) leader = code to n

        val rival = counts.filterKeys { it != code }.values.maxOrNull() ?: 0
        return if (n >= needed && n >= rival * 2) code else null
    }

    private companion object {
        val SHAPE = Regex("[0-9A-Z]{9}")
    }
}
