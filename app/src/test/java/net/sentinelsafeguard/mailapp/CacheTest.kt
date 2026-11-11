package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.data.cache.CacheJson
import net.sentinelsafeguard.mailapp.data.cache.CachePolicy
import net.sentinelsafeguard.mailapp.data.mail.AddressBook
import net.sentinelsafeguard.mailapp.data.mail.MailAttachment
import net.sentinelsafeguard.mailapp.data.mail.MailFolder
import net.sentinelsafeguard.mailapp.data.mail.MailMessage
import net.sentinelsafeguard.mailapp.data.mail.MailSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Offline cache (1.2.0): keys are digests (no address or
 * folder name in a filename), eviction sheds bodies before
 * lists and the folder tree last, and the model JSON is a
 * lossless round trip. */
class CacheTest {

    @Test
    fun keysAreDigestsNotNames() {
        val account = CachePolicy.accountKey("Skyler@SentinelSafeguard.net")
        assertEquals(32, account.length)
        assertEquals(account, CachePolicy.accountKey("skyler@sentinelsafeguard.net"))
        val key = CachePolicy.fileKey(account, CachePolicy.Kind.SUMMARIES, "INBOX")
        assertEquals(64, key.length)
        assertTrue(key.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(
            CachePolicy.fileKey(account, CachePolicy.Kind.SUMMARIES, "INBOX") !=
                CachePolicy.fileKey(account, CachePolicy.Kind.SUMMARIES, "Sent"),
        )
    }

    @Test
    fun evictionShedsBodiesLruFirstAndKeepsFolders() {
        val entries = listOf(
            CachePolicy.EntryInfo("folders", CachePolicy.Kind.FOLDERS, 100, 900),
            CachePolicy.EntryInfo("list-a", CachePolicy.Kind.SUMMARIES, 100, 800),
            CachePolicy.EntryInfo("body-old", CachePolicy.Kind.MESSAGE, 500, 100),
            CachePolicy.EntryInfo("body-new", CachePolicy.Kind.MESSAGE, 500, 700),
        )
        // 1200 cached + 400 incoming under a 1300 cap → shed 300+.
        val plan = CachePolicy.evictionPlan(entries, incomingBytes = 400, capBytes = 1300)
        assertEquals(listOf("body-old"), plan)
        // A huge cap never evicts.
        assertTrue(
            CachePolicy.evictionPlan(entries, 1, capBytes = 10_000).isEmpty(),
        )
        // Even a tiny cap sheds the folder tree only last.
        val all = CachePolicy.evictionPlan(entries, incomingBytes = 2000, capBytes = 100)
        assertEquals("folders", all.last())
        assertEquals("body-old", all.first())
    }

    @Test
    fun folderJsonRoundTrip() {
        val folders = listOf(
            MailFolder("INBOX", 3, false, setOf("\\HasNoChildren")),
            MailFolder("Shared.sentinel.INBOX", 1, true),
        )
        assertEquals(folders, CacheJson.foldersFromJson(CacheJson.foldersToJson(folders)))
        assertNull(CacheJson.foldersFromJson(null))
        assertNull(CacheJson.foldersFromJson("{broken"))
    }

    @Test
    fun summaryJsonRoundTripKeepsThreadingFields() {
        val summaries = listOf(
            MailSummary(
                uid = 42, subject = "Re: Hi", from = "A <a@x>", to = "b@x",
                dateText = "Nov 10", unread = true, snippet = "see attached",
                starred = true, hasAttachments = true, folderName = "INBOX",
                messageId = "<m@x>", inReplyTo = "<p@x>", references = "<r@x> <p@x>",
            ),
        )
        assertEquals(
            summaries,
            CacheJson.summariesFromJson(CacheJson.summariesToJson(summaries)),
        )
    }

    @Test
    fun messageJsonRoundTrip() {
        val message = MailMessage(
            uid = 7, subject = "Report", from = "a@x", to = "b@x", cc = "c@x",
            dateText = "Nov 9", bodyText = "plain", htmlBody = "<p>rich</p>",
            unread = false, starred = true,
            attachments = listOf(
                MailAttachment("1.2", "report.pdf", "application/pdf", 2048),
            ),
            messageId = "<m@x>", references = "<r@x>",
        )
        assertEquals(message, CacheJson.messageFromJson(CacheJson.messageToJson(message)))
        assertNull(CacheJson.messageFromJson("garbage"))
        // HTML-less messages round-trip with a null htmlBody.
        val plain = message.copy(htmlBody = null)
        assertEquals(plain, CacheJson.messageFromJson(CacheJson.messageToJson(plain)))
    }

    // ---- 1.2.2 audit fixes: manifest saved-at (D2), cap guard (D4) ----

    @Test
    fun manifestKeepsSavedAtDistinctFromLastAccess() {
        // D2: the "saved copy" label reads the WRITE time. A
        // record saved Monday and read Friday must still say
        // Monday — the read touches only the eviction clock.
        val entry = CachePolicy.ManifestEntry(
            key = "a".repeat(64),
            kind = CachePolicy.Kind.SUMMARIES,
            sizeBytes = 1234L,
            savedAtMs = 1_000L,
            lastAccessMs = 9_000L,
        )
        val back = CacheJson.manifestFromJson(
            CacheJson.manifestToJson(mapOf(entry.key to entry)),
        )
        assertEquals(mapOf(entry.key to entry), back)
        assertEquals(1_000L, back.getValue(entry.key).savedAtMs)
        assertEquals(9_000L, back.getValue(entry.key).lastAccessMs)
    }

    @Test
    fun legacyManifestRowWithoutSavedFallsBackToAccessTime() {
        // Pre-1.2.2 manifests have no "saved" field: the access
        // time stands in (the old semantics, for old rows only),
        // and a damaged manifest decodes to empty, never throws.
        val legacy = """{"k1":{"kind":"MESSAGE","size":42,"at":777}}"""
        val back = CacheJson.manifestFromJson(legacy)
        assertEquals(777L, back.getValue("k1").savedAtMs)
        assertEquals(777L, back.getValue("k1").lastAccessMs)
        assertEquals(CachePolicy.Kind.MESSAGE, back.getValue("k1").kind)
        assertTrue(CacheJson.manifestFromJson("not json").isEmpty())
        assertTrue(CacheJson.manifestFromJson(null).isEmpty())
    }

    @Test
    fun oversizedRecordIsNeverCached() {
        // D4: one record larger than the whole budget is skipped,
        // not written over an evicted-everything cache.
        assertTrue(CachePolicy.shouldCache(0L))
        assertTrue(CachePolicy.shouldCache(CachePolicy.MAX_TOTAL_BYTES))
        assertTrue(!CachePolicy.shouldCache(CachePolicy.MAX_TOTAL_BYTES + 1L))
        assertTrue(!CachePolicy.shouldCache(-1L))
    }

    @Test
    fun correspondentsRoundTrip() {
        val entries = listOf(
            AddressBook.Entry("a@x", "A <a@x>", 4),
            AddressBook.Entry("b@x", "b@x", 1),
        )
        assertEquals(
            entries,
            CacheJson.correspondentsFromJson(CacheJson.correspondentsToJson(entries)),
        )
        assertTrue(CacheJson.correspondentsFromJson(null).isEmpty())
    }
}
