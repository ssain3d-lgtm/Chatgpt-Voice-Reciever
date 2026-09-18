package com.ssain3d.gptvoicereceiver.core.bridge

import com.ssain3d.gptvoicereceiver.core.session.TurnId

/**
 * The only contract the rest of the system has with "get this text into ChatGPT"
 * (ARCHITECTURE.md §9.5, CHATGPT_BRIDGE.md §2, ADR-006).
 *
 * INV-4: no ChatGPT UI knowledge lives here — no package name, no contentDescription
 * strings, no node-traversal rules. Those exist only inside implementations under
 * `app.chatgptbridge`.
 *
 * Deviation from ARCHITECTURE.md §9.5 for the Spike: the methods are not `suspend`.
 * `core` deliberately has no dependencies (not even kotlinx-coroutines) so it stays a
 * plain JVM library, and the Spike's probes are driven from the main thread with
 * explicit callbacks. §9 marks these signatures as sketches to be finalised in `core`
 * at implementation time; v0.1 may reintroduce `suspend`.
 */
interface ChatBridge {

    /** Bring the target into a state where text can be injected. Injects nothing. */
    fun prepare(turnId: TurnId): BridgeReadiness

    /**
     * Called AT MOST ONCE per [turnId]. Implementations must enforce that themselves
     * via [SendGuard] and return [ChatBridgeResult.SendFailed] with reason
     * `duplicate call` if it happens anyway (CHATGPT_BRIDGE.md §2).
     */
    fun send(turnId: TurnId, text: String): ChatBridgeResult

    fun healthCheck(): BridgeHealth
}

sealed interface BridgeReadiness {
    data object Ready : BridgeReadiness
    data class NotReady(val reason: String, val recoverable: Boolean) : BridgeReadiness
}

/**
 * Staged results. The staging matters: [InjectedButNotSent] and [SendFailed] mean the
 * message may or may not have gone out, so the caller must hand it to the user rather
 * than retry (INV-5, ADR-006: "Missing send is better than duplicate send").
 */
sealed interface ChatBridgeResult {
    val turnId: TurnId

    data class Sent(override val turnId: TurnId, val composerVerified: Boolean) : ChatBridgeResult
    data class InjectedButNotSent(override val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class PrepareFailed(override val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class InjectionFailed(override val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class SendFailed(override val turnId: TurnId, val reason: String) : ChatBridgeResult
    data class BridgeUnavailable(override val turnId: TurnId, val reason: String) : ChatBridgeResult
}

/**
 * True only for results where the message is known to have been delivered.
 *
 * Deliberately false for [ChatBridgeResult.SendFailed]: the click happened but the
 * composer did not clear, so delivery is UNKNOWN. Treating unknown as success would
 * hide a real send; treating it as retryable would risk a duplicate. It is neither.
 */
val ChatBridgeResult.isConfirmedSent: Boolean
    get() = this is ChatBridgeResult.Sent && composerVerified

/**
 * True when the user's text may already have reached ChatGPT even though we did not
 * get a confirmation. Callers must NOT resend on these; they must surface them.
 */
val ChatBridgeResult.isAmbiguous: Boolean
    get() = this is ChatBridgeResult.SendFailed ||
        (this is ChatBridgeResult.Sent && !composerVerified)

data class BridgeHealth(
    val available: Boolean,
    val lastSuccessfulSelector: String? = null,
    val consecutiveFailures: Int = 0,
    val targetAppVersion: String? = null,
    val detail: String? = null,
)
