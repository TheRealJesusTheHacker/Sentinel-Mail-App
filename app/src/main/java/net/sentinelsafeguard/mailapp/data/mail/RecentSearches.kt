package net.sentinelsafeguard.mailapp.data.mail

/**
 * Recent searches (1.2.0) — pure logic. Kept on this device
 * only, newest first, capped. A search is the reader's own
 * words about their own mailbox; it never leaves the phone.
 */
object RecentSearches {

    const val MAX = 8

    // Unit separator: never present in a typed query (add()
    // collapses whitespace), so it cannot split a stored search.
    private const val SEP = "\u001F"

    fun add(current: List<String>, query: String): List<String> {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.isEmpty()) return current
        val rest = current.filterNot { it.equals(q, ignoreCase = true) }
        return (listOf(q) + rest).take(MAX)
    }

    fun encode(list: List<String>): String = list.joinToString(SEP)

    fun decode(text: String?): List<String> =
        text?.split(SEP)?.map { it.trim() }?.filter { it.isNotEmpty() }?.take(MAX)
            ?: emptyList()
}
