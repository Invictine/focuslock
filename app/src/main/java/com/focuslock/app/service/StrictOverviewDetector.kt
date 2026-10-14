package com.focuslock.app.service

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/** Bounded, best-effort inspection of a known launcher's visible overview tree. */
internal object StrictOverviewDetector {
    private const val MAX_NODES = 160
    private const val MAX_DEPTH = 12

    private val overviewViewIds = setOf("overview_panel", "recents_view", "clear_all", "clear_all_button")

    /**
     * Returns canonical known overview IDs visible below a known launcher root.
     * The caller retains ownership of [root]; child nodes obtained here are recycled.
     */
    fun visibleOverviewViewIds(root: AccessibilityNodeInfo?): Set<String> {
        root ?: return emptySet()
        if (!StrictProtectionPolicy.isKnownLauncherPackage(root.packageName?.toString().orEmpty())) {
            return emptySet()
        }
        if (!runCatching { root.isVisibleToUser }.getOrDefault(false)) return emptySet()

        data class PendingNode(val node: AccessibilityNodeInfo, val depth: Int, val callerOwned: Boolean)

        val pending = ArrayDeque<PendingNode>()
        val found = linkedSetOf<String>()
        pending.addLast(PendingNode(root, 0, callerOwned = true))
        var inspected = 0

        try {
            while (pending.isNotEmpty() && inspected < MAX_NODES) {
                val current = pending.removeFirst()
                val node = current.node
                inspected += 1
                try {
                    val visible = runCatching { node.isVisibleToUser }.getOrDefault(false)
                    if (visible) {
                        val canonicalId = runCatching { node.viewIdResourceName }
                            .getOrNull()
                            ?.substringAfterLast('/')
                        if (canonicalId != null && canonicalId in overviewViewIds) found += canonicalId
                    }

                    if (current.depth < MAX_DEPTH && inspected + pending.size < MAX_NODES) {
                        val childCount = runCatching { node.childCount }.getOrDefault(0).coerceAtLeast(0)
                        for (index in 0 until childCount) {
                            if (inspected + pending.size >= MAX_NODES) break
                            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                            pending.addLast(PendingNode(child, current.depth + 1, callerOwned = false))
                        }
                    }
                } finally {
                    if (!current.callerOwned) runCatching { node.recycle() }
                }
            }
        } finally {
            while (pending.isNotEmpty()) {
                val remaining = pending.removeFirst()
                if (!remaining.callerOwned) runCatching { remaining.node.recycle() }
            }
        }

        return found
    }
}
