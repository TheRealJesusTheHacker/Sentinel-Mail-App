package net.sentinelsafeguard.mailapp.data.mail

/**
 * All-folders search result shaping (1.2.0) — pure logic.
 *
 * Each folder's own hits arrive newest-first from the server.
 * Merging across folders cannot honestly sort by time — the
 * summaries carry display dates, not instants — so results are
 * interleaved round-robin across folders: no folder's bulk can
 * crowd out another folder's newest hits, and every row is
 * labeled with its folder on screen, so the shape is visible
 * rather than hidden. The cap is stated in the UI when hit.
 */
object SearchMerge {

    const val TOTAL_CAP = 100
    const val PER_FOLDER_CAP = 25

    fun merge(perFolder: List<List<MailSummary>>, cap: Int = TOTAL_CAP): List<MailSummary> {
        if (cap <= 0) return emptyList()
        val out = ArrayList<MailSummary>(minOf(cap, perFolder.sumOf { it.size }))
        val cursors = IntArray(perFolder.size)
        var remaining = perFolder.sumOf { it.size }
        while (out.size < cap && remaining > 0) {
            for (f in perFolder.indices) {
                if (out.size >= cap) break
                val list = perFolder[f]
                val at = cursors[f]
                if (at < list.size) {
                    out += list[at]
                    cursors[f] = at + 1
                    remaining--
                }
            }
        }
        return out
    }
}
