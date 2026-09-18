package com.ssain3d.gptvoicereceiver.core.bridge

import com.ssain3d.gptvoicereceiver.core.session.TurnId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatBridgeResultTest {

    private val turn = TurnId(1)

    @Test
    fun `only a verified Sent counts as confirmed`() {
        assertTrue(ChatBridgeResult.Sent(turn, composerVerified = true).isConfirmedSent)
        assertFalse(ChatBridgeResult.Sent(turn, composerVerified = false).isConfirmedSent)
        assertFalse(ChatBridgeResult.SendFailed(turn, "composer not cleared").isConfirmedSent)
        assertFalse(ChatBridgeResult.InjectedButNotSent(turn, "send node not found").isConfirmedSent)
    }

    /**
     * INV-5. `SendFailed` means the click happened but we could not verify the result,
     * so delivery is UNKNOWN — it must be reported as ambiguous, never retried.
     */
    @Test
    fun `results where delivery is unknown are flagged ambiguous`() {
        assertTrue(ChatBridgeResult.SendFailed(turn, "composer not cleared").isAmbiguous)
        assertTrue(ChatBridgeResult.Sent(turn, composerVerified = false).isAmbiguous)
    }

    @Test
    fun `results where nothing was sent are not ambiguous`() {
        // Nothing was clicked in these, so the user's text definitely did not go out.
        assertFalse(ChatBridgeResult.InjectedButNotSent(turn, "send node not found").isAmbiguous)
        assertFalse(ChatBridgeResult.PrepareFailed(turn, "composer not found").isAmbiguous)
        assertFalse(ChatBridgeResult.InjectionFailed(turn, "set text rejected").isAmbiguous)
        assertFalse(ChatBridgeResult.BridgeUnavailable(turn, "service off").isAmbiguous)
        assertFalse(ChatBridgeResult.Sent(turn, composerVerified = true).isAmbiguous)
    }

    @Test
    fun `every result carries its turn id`() {
        val results = listOf(
            ChatBridgeResult.Sent(turn, true),
            ChatBridgeResult.InjectedButNotSent(turn, "r"),
            ChatBridgeResult.PrepareFailed(turn, "r"),
            ChatBridgeResult.InjectionFailed(turn, "r"),
            ChatBridgeResult.SendFailed(turn, "r"),
            ChatBridgeResult.BridgeUnavailable(turn, "r"),
        )
        results.forEach { assertEquals(turn, it.turnId) }
    }
}
