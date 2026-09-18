package com.ssain3d.gptvoicereceiver.chatgptbridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * A privacy-scoped description of one accessibility node.
 *
 * Brief §9-2 and SECURITY_PRIVACY.md §6: we must NOT dump ChatGPT's answer text. Only
 * the composer and the Send candidates are of interest, so:
 *
 *   - [textLength] is always recorded; [text] is populated ONLY for editable nodes,
 *     because verifying our own injected text requires reading it back.
 *   - [contentDescription] is kept because it is a UI label and GV-11 exists precisely
 *     to discover its real value (CHATGPT_BRIDGE.md §9: "추측 금지"). It is truncated.
 *   - Message bubbles are excluded upstream by [NodeInspector]'s candidate filter, so
 *     answer content never reaches this type in the first place.
 */
data class NodeSnapshot(
    val index: Int,
    val depth: Int,
    val className: String,
    val viewId: String?,
    val hasText: Boolean,
    val textLength: Int,
    val text: String?,
    val hintText: String?,
    val contentDescription: String?,
    val isEditable: Boolean,
    val isClickable: Boolean,
    val isEnabled: Boolean,
    val isFocusable: Boolean,
    val isFocused: Boolean,
    val isVisibleToUser: Boolean,
    val bounds: Rect,
    val actions: List<String>,
) {
    val supportsSetText: Boolean get() = ACTION_SET_TEXT in actions
    val supportsClick: Boolean get() = ACTION_CLICK in actions

    fun render(): String = buildString {
        append("#").append(index).append(" d").append(depth).append(' ').append(className).append('\n')
        viewId?.let { append("   viewId=").append(it).append('\n') }
        append("   text: has=").append(hasText).append(" len=").append(textLength)
        text?.let { append(" value=\"").append(it).append('"') }
        append('\n')
        hintText?.let { append("   hintText=\"").append(it).append("\"\n") }
        contentDescription?.let { append("   contentDescription=\"").append(it).append("\"\n") }
        append("   editable=").append(isEditable)
            .append(" clickable=").append(isClickable)
            .append(" enabled=").append(isEnabled)
            .append(" focusable=").append(isFocusable)
            .append(" focused=").append(isFocused)
            .append(" visible=").append(isVisibleToUser).append('\n')
        append("   bounds=").append(bounds.toShortString())
            .append(" (").append(bounds.width()).append('x').append(bounds.height()).append(")\n")
        append("   actions=").append(actions.joinToString(",").ifEmpty { "<none>" }).append('\n')
    }

    companion object {
        const val ACTION_SET_TEXT = "SET_TEXT"
        const val ACTION_CLICK = "CLICK"

        /** Truncation limit for any string taken off another app's screen. */
        const val MAX_LABEL = 80

        private val ACTION_NAMES: Map<Int, String> = mapOf(
            AccessibilityNodeInfo.ACTION_CLICK to ACTION_CLICK,
            AccessibilityNodeInfo.ACTION_LONG_CLICK to "LONG_CLICK",
            AccessibilityNodeInfo.ACTION_FOCUS to "FOCUS",
            AccessibilityNodeInfo.ACTION_CLEAR_FOCUS to "CLEAR_FOCUS",
            AccessibilityNodeInfo.ACTION_SELECT to "SELECT",
            AccessibilityNodeInfo.ACTION_SET_TEXT to ACTION_SET_TEXT,
            AccessibilityNodeInfo.ACTION_PASTE to "PASTE",
            AccessibilityNodeInfo.ACTION_COPY to "COPY",
            AccessibilityNodeInfo.ACTION_CUT to "CUT",
            AccessibilityNodeInfo.ACTION_SET_SELECTION to "SET_SELECTION",
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD to "SCROLL_FORWARD",
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD to "SCROLL_BACKWARD",
            AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS to "A11Y_FOCUS",
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY to "NEXT_GRANULARITY",
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY to "PREV_GRANULARITY",
        )

        private fun clip(value: CharSequence?): String? =
            value?.toString()?.takeIf { it.isNotBlank() }?.let {
                if (it.length > MAX_LABEL) it.take(MAX_LABEL) + "…" else it
            }

        fun of(node: AccessibilityNodeInfo, index: Int, depth: Int): NodeSnapshot {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val rawText = node.text
            val editable = node.isEditable

            val actions = node.actionList.map { action ->
                ACTION_NAMES[action.id] ?: "0x%x".format(action.id)
            }

            return NodeSnapshot(
                index = index,
                depth = depth,
                className = node.className?.toString() ?: "<null>",
                viewId = clip(node.viewIdResourceName),
                hasText = !rawText.isNullOrEmpty(),
                textLength = rawText?.length ?: 0,
                // Only editable nodes expose their value — see the class doc.
                text = if (editable) clip(rawText) else null,
                hintText = clip(node.hintText),
                contentDescription = clip(node.contentDescription),
                isEditable = editable,
                isClickable = node.isClickable,
                isEnabled = node.isEnabled,
                isFocusable = node.isFocusable,
                isFocused = node.isFocused,
                isVisibleToUser = node.isVisibleToUser,
                bounds = bounds,
                actions = actions,
            )
        }
    }
}
