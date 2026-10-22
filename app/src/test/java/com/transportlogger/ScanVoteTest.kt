package com.transportlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScanVoteTest {

    @Test
    fun oneReadIsNotEnough() {
        val vote = ScanVote()
        assertNull(vote.offer("012E34567"))
        assertNull(vote.offer("012E34567"))
        assertEquals("012E34567", vote.offer("012E34567"))
    }

    /** The reads Play Services' scanner actually returned for 012E34567. */
    @Test
    fun shortenedReadsNeverCount() {
        val vote = ScanVote()
        for (bad in listOf("057 300", "00E34567", "G1234567", "E34567", "12E34567", "00E34567", "00E34567")) {
            assertNull(vote.offer(bad))
        }
        assertNull(vote.leader)
    }

    @Test
    fun aWrongNineCharacterReadDelaysTheAnswer() {
        val vote = ScanVote()
        assertNull(vote.offer("G12E34567"))
        assertNull(vote.offer("G12E34567"))
        assertNull(vote.offer("012E34567"))
        assertNull(vote.offer("012E34567"))
        assertNull(vote.offer("012E34567"))     // 3 against 2: not clear enough
        assertEquals("012E34567", vote.offer("012E34567"))
    }
}
