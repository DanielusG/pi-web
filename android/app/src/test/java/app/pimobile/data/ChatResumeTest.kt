package app.pimobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResumeTest {

    private fun resume(
        anchorKey: String?,
        detached: Boolean,
        draft: String,
        cursor: Int = 0,
        anchorOffset: Int = 0,
        seenAt: Long = 0L,
    ) = ChatResume(anchorKey, anchorOffset, detached, draft, cursor, seenAt)

    @Test
    fun listKeysFollowTheOrderTheListShows() {
        assertEquals(
            listOf(LOADING_KEY, EARLIER_KEY, EMPTY_KEY, "m1", "m2", STREAMING_KEY, BOTTOM_KEY),
            chatListKeys(true, true, true, listOf("m1", "m2"), true),
        )
        assertEquals(listOf("m1", "m2", BOTTOM_KEY), chatListKeys(false, false, false, listOf("m1", "m2"), false))
    }

    /** The screen's last index comes from this list, so it cannot drift from the LazyColumn. */
    @Test
    fun lastIndexIsTheKeyListSizeMinusOne() {
        val itemKeys = listOf("m1", "m2", "m3")
        for (loading in listOf(true, false)) {
            for (hasMore in listOf(true, false)) {
                for (showEmpty in listOf(true, false)) {
                    for (streaming in listOf(true, false)) {
                        val counted = listOf(loading, hasMore, showEmpty).count { it } +
                            itemKeys.size + (if (streaming) 1 else 0) + 1
                        assertEquals(counted, chatListKeys(loading, hasMore, showEmpty, itemKeys, streaming).size)
                    }
                }
            }
        }
    }

    @Test
    fun anchorResolvesToItsIndex() {
        val keys = chatListKeys(false, true, false, listOf("m1", "m2"), false)
        assertEquals(2, resolveAnchorIndex(keys, "m2"))
        assertEquals(0, resolveAnchorIndex(keys, EARLIER_KEY))
    }

    @Test
    fun aGoneAnchorOrNoAnchorAsksForTheEnd() {
        val keys = chatListKeys(false, false, false, listOf("m1"), false)
        assertNull(resolveAnchorIndex(keys, "gone"))
        assertNull(resolveAnchorIndex(keys, null))
    }

    @Test
    fun prependingKeepsTheItemUnderTheTopEdge() {
        val before = listOf(EARLIER_KEY, "m1", "m2", "m3", BOTTOM_KEY)
        val after = listOf(EARLIER_KEY, "old1", "old2", "m1", "m2", "m3", BOTTOM_KEY)
        // The list kept index 2 ("m2") while the item at it slid down: ask for where it went.
        assertEquals(4 to 12, positionCorrection(before, "m2", 12, after))
    }

    @Test
    fun prependingWhileAtEarlierRowCorrectsToAnchorMessage() {
        // User was at the top tapping earlier; anchor is the real message m1.
        val before = listOf(EARLIER_KEY, "m1", "m2", BOTTOM_KEY)
        val after = listOf(EARLIER_KEY, "old1", "old2", "m1", "m2", BOTTOM_KEY)
        assertEquals(3 to 10, positionCorrection(before, "m1", 10, after))
    }

    @Test
    fun theEarlierRowDisappearingIsCorrectedToo() {
        assertEquals(0 to 5, positionCorrection(listOf(EARLIER_KEY, "m1", "m2", BOTTOM_KEY), "m1", 5, listOf("m1", "m2", BOTTOM_KEY)))
    }

    @Test
    fun appendingBelowLeavesThePositionAlone() {
        val before = listOf(EARLIER_KEY, "m1", "m2", BOTTOM_KEY)
        val after = listOf(EARLIER_KEY, "m1", "m2", STREAMING_KEY, BOTTOM_KEY)
        assertNull(positionCorrection(before, "m1", 0, after))
    }

    @Test
    fun nothingToAnchorOrAnItemThatDroppedOutGoesNowhere() {
        assertNull(positionCorrection(emptyList(), "m1", 0, listOf("m1")))
        assertNull(positionCorrection(listOf("m1", "m2"), "m1", 0, listOf("m2")))
        assertNull(positionCorrection(listOf("m1", "m2"), null, 0, listOf("m1", "m2")))
    }

    @Test
    fun pseudoKeysAreRecognized() {
        assertTrue(isPseudoKey(LOADING_KEY))
        assertTrue(isPseudoKey(EARLIER_KEY))
        assertTrue(isPseudoKey(EMPTY_KEY))
        assertTrue(isPseudoKey(STREAMING_KEY))
        assertTrue(isPseudoKey(BOTTOM_KEY))
        assertFalse(isPseudoKey("m1"))
    }

    @Test
    fun freshSessionIsKeyedByItsDirectory() {
        assertEquals("new:/work/alpha", sessionKeyFor(null, "/work/alpha"))
        assertEquals("new:/work/alpha", sessionKeyFor("", "/work/alpha"))
        assertEquals("abc123", sessionKeyFor("abc123", "/work/alpha"))
    }

    @Test
    fun pruningKeepsTheMostRecent() {
        val entries = (1..40).associate { "s$it" to resume("m", true, "", seenAt = it.toLong()) }
        val pruned = pruneResume(entries, MAX_RESUME_ENTRIES)
        assertEquals(MAX_RESUME_ENTRIES, pruned.size)
        assertTrue(pruned.containsKey("s40"))
        assertFalse(pruned.containsKey("s1"))
    }

    @Test
    fun nothingToSayDropsTheEntry() {
        val store = ChatResumeStore()
        // At the end with an empty composer: where a chat opens anyway, so the entry is noise.
        store.save("s", resume(null, false, "   "))
        assertNull(store.peek("s"))
        store.save("s", resume("m1", true, ""))
        assertNotNull(store.peek("s"))
        store.save("s", resume(null, false, ""))
        assertNull(store.peek("s"))
    }

    @Test
    fun aDraftPastTheCapIsClipped() {
        val store = ChatResumeStore()
        val text = "x".repeat(MAX_DRAFT_CHARS + 100)
        store.save("s", resume(null, false, text, cursor = text.length))
        val saved = store.peek("s")!!
        assertEquals(MAX_DRAFT_CHARS, saved.draft.length)
        assertEquals(MAX_DRAFT_CHARS, saved.cursor)
    }

    @Test
    fun theStoreKeepsOnlyTheMostRecentSessions() {
        val store = ChatResumeStore()
        for (i in 1..40) store.save("s$i", resume("m$i", true, "", seenAt = i.toLong()))
        assertEquals(MAX_RESUME_ENTRIES, store.size)
        assertNotNull(store.peek("s40"))
        assertNull(store.peek("s1"))
    }

    @Test
    fun aFreshSessionsDraftMovesToItsId() {
        val store = ChatResumeStore()
        val fresh = sessionKeyFor(null, "/work/alpha")
        store.save(fresh, resume(null, false, "ciao", cursor = 4))
        store.move(fresh, sessionKeyFor("id1", "/work/alpha"))
        assertNull(store.peek(fresh))
        assertEquals("ciao", store.peek("id1")!!.draft)
    }

    @Test
    fun movingNothingLeavesTheTargetEmpty() {
        val store = ChatResumeStore()
        store.move("nope", "id1")
        assertNull(store.peek("id1"))
    }
}
