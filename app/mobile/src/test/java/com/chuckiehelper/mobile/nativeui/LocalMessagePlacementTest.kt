package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class LocalMessagePlacementTest {
    @Test fun actualSubmissionProjectionDoesNotJumpAheadOfEarlierSavedAssistant() {
        val old = HermesMessage("user", "start", 900, timestamp = 1000)
        val earlier = HermesMessage("assistant", "saved after send", 7, timestamp = 2000)
        val pending = HermesMessage("user", "interjection", localKey = "send", timestamp = 3000)
        assertEquals(listOf(old, earlier, pending), projectCurrentSubmission(listOf(old, earlier), pending, setOf(900)))
    }
    @Test fun currentSubmissionPrecedesNewerNativeReplyWhenTimesAreKnown() {
        val old = HermesMessage("user", "start", 900, timestamp = 1000)
        val reply = HermesMessage("assistant", "next reply", 7, timestamp = 4000)
        val pending = HermesMessage("user", "interjection", localKey = "send", timestamp = 3000)
        assertEquals(listOf(old, pending, reply), projectCurrentSubmission(listOf(old, reply), pending, setOf(900)))
    }
    @Test fun newlySavedOlderNarrationRemainsBeforeTheLaterInterjection() {
        val start=HermesMessage("user", "start", 900, timestamp=1000)
        val narration=HermesMessage("assistant", "22:57 reply", 7, timestamp=2000, narration=true)
        val pending=SteeringMessage("send", "session", "22:58 input", setOf(900), 900, "已送达", 3000)
        assertEquals(listOf("start","22:57 reply","22:58 input"),
            mergeSteeringMessages(listOf(start,narration),listOf(pending)).map { it.text })
    }
    @Test fun anUnconfirmedInitialSubmissionPrecedesItsAlreadySavedReply() {
        val old=HermesMessage("assistant", "old", 90, timestamp=1000)
        val reply=HermesMessage("assistant", "reply", 2, timestamp=3000)
        val pending=HermesMessage("user", "input", localKey="send", timestamp=2000)
        assertEquals(listOf(old,pending,reply),insertLocalMessage(listOf(old,reply),pending,setOf(90)))
    }
    @Test fun multiplePendingInterjectionsRemainChronologicalWithoutSortingNativeHistory() {
        val old=HermesMessage("user","start",99,timestamp=1000)
        val first=SteeringMessage("a","s","first",setOf(99),99,timestamp=2000)
        val second=first.copy(key="b",text="second",timestamp=4000)
        val reply=HermesMessage("assistant","between",2,timestamp=3000)
        assertEquals(listOf("start","first","between","second"),mergeSteeringMessages(listOf(old,reply),listOf(first,second)).map { it.text })
    }
    @Test fun narrationReplayCannotMoveTheCanonicalReplyBehindTheNewUser() {
        val old=HermesMessage("user","start",99,timestamp=1000)
        val reply=HermesMessage("assistant","older streamed reply",narrationMessageId("reply"),timestamp=2000,narration=true)
        val pending=SteeringMessage("b","s","new input",setOf(99),99,"已送达",3000)
        val note=AssistantNarration("narration-run-item","s",reply.text,99,"start",2000,1000,run="run",messageId="reply",streamed=true)
        val once=mergeAssistantNarrations(mergeSteeringMessages(listOf(old,reply),listOf(pending)),listOf(note))
        val twice=mergeAssistantNarrations(once,listOf(note))
        assertEquals(listOf("start","older streamed reply","new input"),twice.map { it.text })
        assertEquals(once.map { it.localKey },twice.map { it.localKey })
    }
}
