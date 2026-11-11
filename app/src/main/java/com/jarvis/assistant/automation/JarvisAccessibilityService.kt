package com.jarvis.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("JarvisAccess", "Deep automation service connected.")
    }

    /** Package of the app currently on screen (used to verify that an app really opened). */
    @Volatile var foregroundPackage: String? = null
        private set

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui") return
        foregroundPackage = pkg
        try {
            (applicationContext as? com.jarvis.assistant.JarvisApp)?.core?.onForegroundPackageChanged(pkg)
        } catch (_: Exception) {}
    }
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun findNode(matcher: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchTree(root, matcher)
    }

    private fun searchTree(node: AccessibilityNodeInfo, matcher: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (matcher(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            searchTree(child, matcher)?.let { return it }
        }
        return null
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun clickElementByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (node in root.findAccessibilityNodeInfosByText(text)) {
            if (clickNode(node)) return true
        }
        return false
    }

    fun typeText(node: AccessibilityNodeInfo, text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Types into the focused editable field, or the first editable one on screen. */
    fun typeIntoFocused(text: String): Boolean {
        val field = findNode { it.isEditable && it.isFocused } ?: findNode { it.isEditable } ?: return false
        return typeText(field, text)
    }

    fun scrollForward(): Boolean {
        val node = findNode { it.isScrollable } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
    }


    // ---- screen reading for the UI agent -------------------------------------------------------

    @Volatile private var lastNodes: List<AccessibilityNodeInfo> = emptyList()

    /** Numbered list of what is visible. Password fields are never read. Indexes feed clickIndex/typeIndex. */
    fun describeScreen(maxNodes: Int = 60): String {
        val root = rootInActiveWindow ?: return "(screen not readable)"
        val nodes = mutableListOf<AccessibilityNodeInfo>()
        collectVisible(root, nodes, maxNodes)
        lastNodes = nodes
        return buildString {
            append("App: ").append(root.packageName).append('\n')
            nodes.forEachIndexed { i, n ->
                val label = when {
                    n.isPassword -> "(password field)"
                    else -> (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.take(70)
                        ?: n.viewIdResourceName?.substringAfter('/')
                        ?: ""
                }
                val flags = buildList {
                    if (n.isEditable) add("editable")
                    if (n.isClickable) add("clickable")
                    if (n.isScrollable) add("scrollable")
                    if (n.isChecked) add("checked")
                }.joinToString(",")
                append('[').append(i).append("] ")
                append(n.className?.toString()?.substringAfterLast('.') ?: "View")
                append(" \"").append(label).append("\" ").append(flags).append('\n')
            }
        }
    }

    private fun collectVisible(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>, max: Int) {
        if (out.size >= max || !node.isVisibleToUser) return
        val label = node.text ?: node.contentDescription
        if (!label.isNullOrBlank() || node.isClickable || node.isEditable || node.isScrollable) out.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectVisible(child, out, max)
        }
    }

    fun clickIndex(index: Int): Boolean {
        val node = lastNodes.getOrNull(index) ?: return false
        if (node.isEditable) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return clickNode(node)
    }

    fun typeIndex(index: Int, text: String): Boolean {
        val node = lastNodes.getOrNull(index) ?: return false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        return typeText(node, text)
    }

    fun scrollBackward(): Boolean {
        val node = findNode { it.isScrollable } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    /** Presses a Send button in the current app (SMS, email, most chat apps). */
    suspend fun pressSend(): Boolean = withContext(Dispatchers.Default) {
        repeat(8) {
            delay(600)
            val send = findNode {
                it.contentDescription?.toString()?.contains("send", ignoreCase = true) == true ||
                    it.viewIdResourceName?.contains("send", ignoreCase = true) == true
            }
            if (send != null && clickNode(send)) return@withContext true
        }
        false
    }

    fun tapAt(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * Call right after opening a WhatsApp chat. WhatsApp can take a couple of seconds to draw,
     * so this polls for the message box and the Send button instead of waiting a fixed time.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun automateWhatsAppSend(contactName: String, messageText: String): Boolean =
        withContext(Dispatchers.Default) {
            repeat(12) {
                delay(700)
                val entry = findNode { it.isEditable }
                if (entry != null) {
                    if (entry.text.isNullOrBlank()) typeText(entry, messageText)
                    delay(400)
                    val send = findNode {
                        it.contentDescription?.toString()?.equals("Send", ignoreCase = true) == true ||
                            it.viewIdResourceName?.endsWith(":id/send") == true
                    }
                    if (send != null) return@withContext clickNode(send)
                }
            }
            false
        }
}
