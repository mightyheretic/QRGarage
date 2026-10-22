package com.example.qrgarage.data.repo

/**
 * LOC-10..13 / LOC-16: wheel values for shelf, row and bin, the uniform label generated from
 * them (e.g. "Shelf A Row 01 Bin 2"), and the no-break-inside-a-segment display rule.
 */
object LocationLabel {

    /** Selected wheel values. [row] and [bin] are optional; a location may sit on a bare shelf. */
    data class Parts(val shelf: String, val row: Int? = null, val bin: Int? = null) {
        init {
            require(shelfIndex(shelf) != null) { "Invalid shelf letters: $shelf" }
            require(row == null || row >= 1) { "Row must be >= 1" }
            require(bin == null || bin >= 1) { "Bin must be >= 1" }
        }

        val label: String get() = generate(this)
    }

    /** Shelf wheel length: A..Z then AA..ZZ (702 values). */
    const val SHELF_COUNT = 26 + 26 * 26
    /** Default visible range for the row and bin wheels (1-99), extended if a stored value is larger. */
    const val NUMBER_COUNT = 99

    private const val NBSP = ' '
    private val PARSE = Regex("""^\s*Shelf\s+([A-Za-z]+)(?:\s+Row\s+(\d+))?(?:\s+Bin\s+(\d+))?\s*$""", RegexOption.IGNORE_CASE)
    private val SEGMENT = Regex("""\b(Shelf|Row|Bin) (\S+)""")

    /** LOC-10: 0 → A, 25 → Z, 26 → AA, 27 → AB … (spreadsheet-style bijective base 26). */
    fun shelfLetters(index: Int): String {
        require(index >= 0)
        var n = index + 1
        val sb = StringBuilder()
        while (n > 0) {
            val rem = (n - 1) % 26
            sb.append('A' + rem)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    /** Inverse of [shelfLetters]; null for anything that is not 1+ letters. */
    fun shelfIndex(letters: String): Int? {
        if (letters.isEmpty() || letters.length > 6) return null
        var n = 0
        for (c in letters.uppercase()) {
            if (c !in 'A'..'Z') return null
            n = n * 26 + (c - 'A' + 1)
        }
        return n - 1
    }

    /** LOC-11: always at least two digits. */
    fun rowText(row: Int): String = row.toString().padStart(2, '0')

    /** LOC-12: no padding. */
    fun binText(bin: Int): String = bin.toString()

    /** LOC-13: the type word is spelled out before each value. */
    fun generate(parts: Parts): String = buildString {
        append("Shelf ").append(parts.shelf.uppercase())
        parts.row?.let { append(" Row ").append(rowText(it)) }
        parts.bin?.let { append(" Bin ").append(binText(it)) }
    }

    /** The label after the shelf segment, e.g. "Row 01 Bin 2"; empty for a bare shelf. */
    fun afterShelf(parts: Parts): String = generate(parts).removePrefix("Shelf ${parts.shelf.uppercase()}").trim()

    /** Recognizes a generated label, including hand-typed v1 names in the same shape. */
    fun parse(label: String): Parts? {
        val m = PARSE.matchEntire(label) ?: return null
        val shelf = m.groupValues[1].uppercase()
        if (shelfIndex(shelf) == null) return null
        val row = m.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
        val bin = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (row == 0 || bin == 0) return null
        return Parts(shelf, row, bin)
    }

    /**
     * LOC-16: joins each type word to its value with a no-break space, so text layout (Compose
     * and the PDF's StaticLayout alike) can only wrap between segments: `Shelf A` | `Row 01` | `Bin 2`.
     */
    fun unbreakable(text: String): String = SEGMENT.replace(text) { "${it.groupValues[1]}$NBSP${it.groupValues[2]}" }
}
