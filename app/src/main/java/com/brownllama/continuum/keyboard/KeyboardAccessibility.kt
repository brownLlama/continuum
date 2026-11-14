package com.brownllama.continuum.keyboard

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityManager
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper

/**
 * One thing on the keyboard a screen reader can land on: where it is (view pixels), what it is
 * called, and what activating it (a double tap in TalkBack) does.
 */
internal class AccessNode(val bounds: Rect, val label: String, val activate: () -> Unit)

/**
 * Lets screen readers (TalkBack) see [KeyboardView], which draws everything itself and so has no
 * child views to describe: each key, suggestion and grid cell is offered as a virtual view with a
 * name and a click.
 *
 * The view lists what it shows now through [nodes]; the list is read again after [changed].
 * Nothing here runs unless an accessibility service is on.
 */
internal class KeyboardAccessibility(
    private val view: KeyboardView,
    private val nodes: () -> List<AccessNode>,
) : ExploreByTouchHelper(view) {

    private val manager = view.context.getSystemService(AccessibilityManager::class.java)
    /** What the view showed when last asked; a node's id is its place in this list. */
    private var current: List<AccessNode> = emptyList()
    private var stale = true

    /** The keyboard shows something else now (another panel, new suggestions, a scroll). */
    fun changed() {
        stale = true
        if (manager?.isEnabled == true) invalidateRoot()
    }

    private fun refresh() {
        if (!stale) return
        // A screen reader asking must never be able to take the keyboard down with it.
        current = runCatching(nodes).getOrDefault(emptyList())
        stale = false
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        refresh()
        val i = current.indexOfFirst { it.bounds.contains(x.toInt(), y.toInt()) }
        return if (i >= 0) i else INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        refresh()
        for (i in current.indices) virtualViewIds += i
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val n = current.getOrNull(virtualViewId)
        // Every node needs a name and a place, also one the keyboard has since stopped showing.
        node.contentDescription = n?.label?.takeIf { it.isNotEmpty() } ?: " "
        @Suppress("DEPRECATION") // The helper works out the on-screen bounds from these.
        node.setBoundsInParent(n?.bounds?.takeIf { !it.isEmpty } ?: NOWHERE)
        if (n == null) return
        node.className = BUTTON
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val n = current.getOrNull(virtualViewId) ?: return false
        n.activate()
        return true
    }

    private companion object {
        val NOWHERE = Rect(0, 0, 1, 1)
        const val BUTTON = "android.widget.Button"
    }
}
