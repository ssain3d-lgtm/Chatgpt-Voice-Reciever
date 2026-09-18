package com.ssain3d.gptvoicereceiver.chatgptbridge

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Finds the composer and Send candidates in ChatGPT's accessibility tree.
 *
 * Deliberate non-goals:
 *   - No guessed `resource-id`. A Compose UI has none unless the developer opted in
 *     (ANDROID_CONSTRAINTS.md §9), so matching on one would be fiction.
 *   - No guessed contentDescription. GV-11 exists to DISCOVER the real Send label; the
 *     inspector reports what it finds and ranks by structure, so the report stays
 *     valid whatever language or wording the app ships.
 *   - No absolute coordinates anywhere (INV-9). Position is used only as a relative
 *     score against the screen rectangle.
 *
 * Privacy: the candidate filter keeps only editable or clickable nodes, so message
 * bubbles — the answer text — are never collected (brief §9-2).
 */
object NodeInspector {

    /** Guard rails so a pathological tree cannot hang the UI thread. */
    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 60

    /** CHATGPT_BRIDGE.md Selector 2: composer expected in the lower part of the screen. */
    private const val COMPOSER_BOTTOM_FRACTION = 0.60f

    data class Report(
        val screen: Rect,
        val totalNodesVisited: Int,
        val candidates: List<NodeSnapshot>,
        val composerIndex: Int?,
        val sendIndex: Int?,
        val notes: List<String>,
    ) {
        val composer: NodeSnapshot? get() = composerIndex?.let { i -> candidates.firstOrNull { it.index == i } }
        val send: NodeSnapshot? get() = sendIndex?.let { i -> candidates.firstOrNull { it.index == i } }

        fun render(): String = buildString {
            appendLine("screen=${screen.toShortString()} nodesVisited=$totalNodesVisited")
            appendLine("candidates=${candidates.size}")
            appendLine("composer=${composerIndex ?: "NOT FOUND"}  send=${sendIndex ?: "NOT FOUND"}")
            notes.forEach { appendLine("note: $it") }
            appendLine()
            candidates.forEach { append(it.render()).append('\n') }
        }
    }

    /**
     * A scan result that keeps the live nodes alongside their snapshots, so the probe
     * can act on exactly the node it reported. Re-scan before every action: nodes go
     * stale as soon as the app redraws.
     */
    class Scan(
        val report: Report,
        private val live: Map<Int, AccessibilityNodeInfo>,
    ) {
        fun composerNode(): AccessibilityNodeInfo? = report.composerIndex?.let { live[it] }
        fun sendNode(): AccessibilityNodeInfo? = report.sendIndex?.let { live[it] }
        fun node(index: Int): AccessibilityNodeInfo? = live[index]
    }

    /**
     * Walks the tree breadth-first and keeps interactive nodes only.
     *
     * @param root must already have been confirmed to belong to ChatGPT by
     *             [ChatGptAccessibilityService.activeChatGptRoot].
     */
    fun scan(root: AccessibilityNodeInfo, screen: Rect): Scan {
        val candidates = mutableListOf<NodeSnapshot>()
        val live = mutableMapOf<Int, AccessibilityNodeInfo>()
        val notes = mutableListOf<String>()
        var visited = 0
        var index = 0

        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val (node, depth) = queue.removeFirst()
            visited++

            if (isCandidate(node)) {
                val i = index++
                candidates += NodeSnapshot.of(node, i, depth)
                live[i] = node
            }

            if (depth < MAX_DEPTH) {
                for (i in 0 until node.childCount) {
                    val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                    queue.add(child to depth + 1)
                }
            }
        }

        if (visited >= MAX_NODES) notes += "node cap ($MAX_NODES) reached — tree truncated"
        if (candidates.isEmpty()) notes += "no editable or clickable node found at all"

        val composer = pickComposer(candidates, screen, notes)
        val send = composer?.let { pickSend(candidates, it, screen, notes) }

        return Scan(
            report = Report(
                screen = screen,
                totalNodesVisited = visited,
                candidates = candidates,
                composerIndex = composer?.index,
                sendIndex = send?.index,
                notes = notes,
            ),
            live = live,
        )
    }

    fun inspect(root: AccessibilityNodeInfo, screen: Rect): Report = scan(root, screen).report

    /** Interactive nodes only. This is what keeps conversation text out of the report. */
    private fun isCandidate(node: AccessibilityNodeInfo): Boolean =
        node.isVisibleToUser && (node.isEditable || node.isClickable)

    /**
     * CHATGPT_BRIDGE.md Selector Stack 1–2: semantic state first, position only as a
     * tiebreak. Scored rather than first-match so the report can explain itself.
     */
    private fun pickComposer(
        candidates: List<NodeSnapshot>,
        screen: Rect,
        notes: MutableList<String>,
    ): NodeSnapshot? {
        val editable = candidates.filter { it.isEditable && it.isVisibleToUser }
        if (editable.isEmpty()) {
            notes += "NO EDITABLE NODE — this is the GV-11 failure case; Plan A cannot work as designed"
            return null
        }
        if (editable.size > 1) {
            notes += "${editable.size} editable nodes; picked by score (see below)"
        }

        val best = editable.maxByOrNull { node ->
            var score = 0
            if (node.supportsSetText) score += 40   // the action Plan A actually needs
            if (node.isFocusable) score += 10
            if (node.isEnabled) score += 5
            // Lower on screen is more composer-like.
            val centerY = node.bounds.centerY().toFloat()
            if (screen.height() > 0 && centerY > screen.height() * COMPOSER_BOTTOM_FRACTION) score += 20
            // A composer is wide; a stray search box in a toolbar usually is not.
            if (screen.width() > 0 && node.bounds.width() > screen.width() * 0.5f) score += 10
            if (node.isFocused) score += 5
            score
        }

        if (best != null && !best.supportsSetText) {
            notes += "composer candidate #${best.index} does NOT advertise ACTION_SET_TEXT — " +
                "expect GV-12 to fail; the clipboard + ACTION_PASTE path would be next"
        }
        return best
    }

    /**
     * Send candidates are ranked by structure, never by a guessed label
     * (CHATGPT_BRIDGE.md §9 forbids guessing the contentDescription).
     *
     * Heuristics, in order of weight: clickable, small and button-shaped, positioned
     * to the right of / below the composer, vertically close to it.
     */
    private fun pickSend(
        candidates: List<NodeSnapshot>,
        composer: NodeSnapshot,
        screen: Rect,
        notes: MutableList<String>,
    ): NodeSnapshot? {
        val clickable = candidates.filter {
            it.isClickable && it.isVisibleToUser && it.index != composer.index
        }
        if (clickable.isEmpty()) {
            notes += "no clickable node besides the composer — Send cannot be located"
            return null
        }

        val composerCenterY = composer.bounds.centerY()
        val screenW = screen.width().coerceAtLeast(1)
        val screenH = screen.height().coerceAtLeast(1)

        val best = clickable.maxByOrNull { node ->
            var score = 0
            if (node.supportsClick) score += 20
            if (node.isEnabled) score += 10

            // Button-shaped: small and roughly square.
            val w = node.bounds.width()
            val h = node.bounds.height()
            if (w in 1..(screenW / 4) && h in 1..(screenH / 8)) score += 25
            if (h > 0 && w > 0) {
                val ratio = w.toFloat() / h.toFloat()
                if (ratio in 0.6f..1.8f) score += 15
            }

            // Vertically aligned with the composer row.
            val dy = kotlin.math.abs(node.bounds.centerY() - composerCenterY)
            if (dy < screenH * 0.10f) score += 30
            else if (dy < screenH * 0.20f) score += 10

            // To the right of the composer's centre: the conventional Send position,
            // but only a nudge, never a requirement.
            if (node.bounds.centerX() > composer.bounds.centerX()) score += 10

            score
        }

        if (best != null) {
            notes += "Send candidate #${best.index}: contentDescription=" +
                (best.contentDescription?.let { "\"$it\"" } ?: "<none>") +
                " enabled=${best.isEnabled} — record this value in GV-11, it is the " +
                "real label (do not guess)"
            if (!best.isEnabled) {
                notes += "Send candidate is currently DISABLED — expected while the " +
                    "composer is empty; re-inspect after injecting text"
            }
        }
        return best
    }
}
