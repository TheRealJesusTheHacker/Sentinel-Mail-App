package net.sentinelsafeguard.mailapp.data.mail

/**
 * Conversation threading for the message list (1.2.0) — pure
 * logic, no mail library, unit-tested directly.
 *
 * A thread is identified by the root of its References chain
 * when the headers were fetched; the chain's first entry is the
 * conversation's first message on every participant's copy, so
 * it is the one key all members share. With no References, the
 * In-Reply-To parent is the best available anchor; with no
 * headers at all, the message stands alone under its own
 * Message-ID; and with nothing, the normalized subject groups
 * it (the classic fallback — "Re: Project kickoff" and "Project
 * kickoff" are one conversation even when a client sent no
 * threading headers).
 *
 * Grouping covers the messages in ONE fetched list (one page of
 * one folder) — that is the honest scope, and it is the scope
 * the UI claims. Threads do not reach across folders or pages.
 */
object Threading {

    private val ID_TOKEN = Regex("<[^>\\s]+>")

    /** Message-ID tokens out of a References / In-Reply-To
     * header, in the order the sender wrote them. */
    fun parseReferences(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return ID_TOKEN.findAll(header).map { it.value }.toList()
    }

    /** One message's thread key, from its own headers. */
    fun threadKey(
        messageId: String?,
        references: String?,
        inReplyTo: String?,
        subject: String,
    ): String {
        val refs = parseReferences(references)
        if (refs.isNotEmpty()) return "ref:" + refs.first()
        val parent = parseReferences(inReplyTo).firstOrNull()
            ?: inReplyTo?.trim()?.takeIf { it.isNotEmpty() }
        if (parent != null) return "ref:$parent"
        val own = messageId?.trim()?.takeIf { it.isNotEmpty() }
        if (own != null) return "ref:$own"
        return "subj:" + normalizedSubject(subject)
    }

    private val PREFIX = Regex("(?i)^(re|fwd|fw)(\\[\\d+])?\\s*:\\s*")

    /** A subject with its reply/forward prefixes stripped and
     * case folded — the grouping form, never a display form. */
    fun normalizedSubject(subject: String): String {
        var s = subject.trim()
        while (true) {
            val m = PREFIX.find(s) ?: break
            s = s.substring(m.range.last + 1).trim()
        }
        return s.lowercase()
    }

    /** One conversation as the list renders it: members keep the
     * list's own order (newest first), so [latest] is the row's
     * face and the rest unfold beneath it. */
    data class Group(
        val key: String,
        val members: List<MailSummary>,
    ) {
        val latest: MailSummary get() = members.first()
        val count: Int get() = members.size
        val hasUnread: Boolean get() = members.any { it.unread }
    }

    /**
     * Group a fetched list. Groups come out in the order their
     * newest member appears in the input — the input is already
     * newest-first, so the list's shape does not change, it only
     * folds.
     */
    fun group(summaries: List<MailSummary>): List<Group> {
        val byKey = LinkedHashMap<String, MutableList<MailSummary>>()
        for (s in summaries) {
            val key = threadKey(s.messageId, s.references, s.inReplyTo, s.subject)
            byKey.getOrPut(key) { mutableListOf() } += s
        }
        return byKey.entries.map { (key, members) -> Group(key, members.toList()) }
    }
}
