package net.sentinelsafeguard.mailapp.data.mail

/**
 * Recipient autocomplete's brain (1.2.0) — pure logic.
 *
 * The address book is harvested ONLY from the signed-in
 * account's own mail: every From and To the app has already
 * fetched, in lists and in the offline cache. No contacts
 * permission, no device address book, no third source — the
 * correspondents a mail app may suggest are the people this
 * mailbox already talks to.
 */
object AddressBook {

    /** One correspondent: the bare [address] (lowercase, the
     * identity), the [display] form as most recently seen
     * ("Name <a@b>" or the bare address), and how often the
     * mailbox has exchanged mail with them. */
    data class Entry(
        val address: String,
        val display: String,
        val count: Int,
    )

    /**
     * Fold one batch of summaries into the book. The batch
     * arrives newest-first: the FIRST sighting of an address in
     * the batch is its most recent display form. [ownAddress] is
     * never its own correspondent.
     */
    fun harvest(
        batch: List<MailSummary>,
        ownAddress: String,
        existing: List<Entry> = emptyList(),
    ): List<Entry> {
        val own = ownAddress.trim().lowercase()
        val byAddress = LinkedHashMap<String, Entry>()
        for (e in existing) byAddress[e.address] = e
        // Addresses whose display form this batch has already
        // refreshed: the batch is newest-first, so the FIRST
        // sighting carries the current form and later sightings
        // in the same batch must not overwrite it with an older
        // one.
        val refreshed = mutableSetOf<String>()
        fun offer(formatted: String) {
            val bare = MailText.extractAddress(formatted).trim().lowercase()
            if (bare.isEmpty() || bare == own || !bare.contains('@')) return
            val current = byAddress[bare]
            val display = if (bare in refreshed) {
                current?.display ?: formatted.trim()
            } else {
                refreshed += bare
                formatted.trim()
            }
            byAddress[bare] = Entry(
                address = bare,
                display = display.ifBlank { bare },
                count = (current?.count ?: 0) + 1,
            )
        }
        for (summary in batch) {
            offer(summary.from)
            for (addr in MailText.parseAddressList(summary.to)) offer(addr)
        }
        return byAddress.values.sortedWith(
            compareByDescending<Entry> { it.count }.thenBy { it.address },
        )
    }

    /** The partial address being typed: the text after the last
     * comma/semicolon in the field, trimmed. */
    fun currentToken(field: String): String =
        field.split(',', ';').lastOrNull()?.trim() ?: ""

    /**
     * Suggestions for [token]: correspondents whose address or
     * display name starts with or contains the token (a prefix
     * match on the address's local part outranks everything),
     * most-written-to first. An empty token suggests nothing —
     * the dropdown appears when there is something to complete.
     */
    fun suggest(entries: List<Entry>, token: String, limit: Int = 6): List<Entry> {
        val t = token.trim().lowercase()
        if (t.isEmpty()) return emptyList()
        val matches = entries.filter { e ->
            e.address.contains(t) ||
                MailText.extractAddress(e.display).lowercase().contains(t) ||
                e.display.lowercase().contains(t)
        }
        return matches.sortedWith(
            compareByDescending<Entry> {
                it.address.substringBefore('@').startsWith(t)
            }.thenByDescending { it.count }.thenBy { it.address },
        ).take(limit)
    }

    /** The field after picking [entry]: the current token is
     * replaced by the correspondent's display form, ready for
     * the next recipient. */
    fun applyPick(field: String, entry: Entry): String {
        val cut = maxOf(field.lastIndexOf(','), field.lastIndexOf(';'))
        val head = if (cut >= 0) field.substring(0, cut + 1) + " " else ""
        return head + entry.display + ", "
    }
}
