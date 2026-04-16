package com.atwenty.personalagent.service.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.atwenty.personalagent.PersonalAgentApp
import com.atwenty.personalagent.domain.model.UiNode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

class PersonalAgentAccessibilityService : AccessibilityService(), AccessibilityDriver {

    companion object {
        private const val TAG = "PA_Accessibility"
        private const val SCREENSHOT_WIDTH = 720
        private const val SCREENSHOT_HEIGHT = 1600
        private const val MAX_ROOT_RETRIES = 3
        private const val ROOT_RETRY_DELAY_MS = 300L

        // Singleton reference so Orchestrator can access this service
        var instance: PersonalAgentAccessibilityService? = null
            private set
    }

    private var nodeCounter = 0
    private val nodeMap = mutableMapOf<String, AccessibilityNodeInfo>()

    // Track window change events for the orchestrator's verification loop
    var lastWindowChangeTimestamp: Long = 0L
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                lastWindowChangeTimestamp = System.currentTimeMillis()
            }
        }

        // Check if current foreground app is in the blacklist (Privacy Safe-Zone)
        val packageName = event.packageName?.toString()
        if (packageName != null) {
            val app = application as? PersonalAgentApp
            if (app?.settingsRepository?.isPackageBlacklisted(packageName) == true) {
                Log.d(TAG, "Private Mode: skipping event from blacklisted package $packageName")
                return
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // --- AccessibilityDriver Implementation ---

    override fun isReady(): Boolean = instance != null

    override fun getCurrentPackage(): String? {
        return try {
            getRootSafe()?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    override fun readScreen(): List<UiNode> {
        nodeCounter = 0
        nodeMap.clear()
        val root = getRootSafe() ?: return emptyList()
        return listOfNotNull(compactNode(root))
    }

    override fun readScreenAsText(): String {
        val nodes = readScreen()
        if (nodes.isEmpty()) return "[Screen could not be read - no accessibility root available]"
        val sb = StringBuilder()
        sb.appendLine("=== Current Screen UI Tree ===")
        nodes.forEach { sb.append(it.toCompactString()) }
        sb.appendLine("=== End UI Tree ===")
        return sb.toString()
    }

    override fun clickNode(nodeId: String): Boolean {
        val node = nodeMap[nodeId] ?: run {
            Log.w(TAG, "clickNode: node $nodeId not found in current tree")
            return false
        }
        return try {
            // Try clicking the node directly
            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                // Walk up to find nearest clickable parent
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    parent = parent.parent
                }
                // Last resort: tap the center of the node's bounds via gesture
                tapAtNodeCenter(node)
            }
        } catch (e: Exception) {
            Log.e(TAG, "clickNode failed for $nodeId", e)
            false
        }
    }

    override fun typeText(text: String, nodeId: String?): Boolean {
        return try {
            val targetNode = if (nodeId != null) {
                nodeMap[nodeId]
            } else {
                findFocusedEditableNode()
            }

            if (targetNode == null) {
                Log.w(TAG, "typeText: no target node found")
                return false
            }

            // Focus the node first
            targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // Clear existing text
            val clearArgs = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                )
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

            // Set the text
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            Log.e(TAG, "typeText failed", e)
            false
        }
    }

    override fun scroll(direction: String, nodeId: String?): Boolean {
        return try {
            val targetNode = if (nodeId != null) {
                nodeMap[nodeId]
            } else {
                findScrollableNode()
            }

            if (targetNode == null) {
                Log.w(TAG, "scroll: no scrollable node found")
                return false
            }

            when (direction.lowercase()) {
                "down" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                "up" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                "right" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                "left" -> targetNode.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "scroll failed", e)
            false
        }
    }

    override fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    override fun pressRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    override fun hideKeyboard(): Boolean {
        // There is no guaranteed API to dismiss keyboard via AccessibilityService.
        // Best effort: press back, which usually dismisses it.
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    override suspend fun takeScreenshot(): String? {
        val currentPkg = getCurrentPackage()
        if (currentPkg != null) {
            val app = application as? PersonalAgentApp
            if (app?.settingsRepository?.isPackageBlacklisted(currentPkg) == true) {
                Log.w(TAG, "takeScreenshot: Private Mode active for $currentPkg. Screenshot blocked.")
                return null
            }
        }

        return suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    0, // displayId
                    mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            try {
                                val hwBitmap = Bitmap.wrapHardwareBuffer(
                                    screenshot.hardwareBuffer,
                                    screenshot.colorSpace
                                )
                                screenshot.hardwareBuffer.close()

                                if (hwBitmap == null) {
                                    continuation.resume(null)
                                    return
                                }

                                // Convert to software bitmap for manipulation
                                val softBitmap = hwBitmap.copy(Bitmap.Config.ARGB_8888, false)
                                hwBitmap.recycle()

                                // Downscale to reduce token usage
                                val scaledBitmap = Bitmap.createScaledBitmap(
                                    softBitmap, SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true
                                )
                                softBitmap.recycle()

                                // Encode to Base64
                                val stream = ByteArrayOutputStream()
                                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                                scaledBitmap.recycle()

                                val base64 = Base64.encodeToString(
                                    stream.toByteArray(), Base64.NO_WRAP
                                )
                                continuation.resume(base64)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to process screenshot", e)
                                continuation.resume(null)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            Log.e(TAG, "Screenshot failed with error code: $errorCode")
                            continuation.resume(null)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "takeScreenshot exception", e)
                continuation.resume(null)
            }
        }
    }

    // --- Private Helpers ---

    /**
     * Safely get the root node with retries for stale windows / transitions.
     */
    private fun getRootSafe(): AccessibilityNodeInfo? {
        repeat(MAX_ROOT_RETRIES) { attempt ->
            val root = try {
                rootInActiveWindow
            } catch (e: Exception) {
                null
            }
            if (root != null) {
                // Privacy Check
                val pkg = root.packageName?.toString()
                if (pkg != null) {
                    val app = application as? PersonalAgentApp
                    if (app?.settingsRepository?.isPackageBlacklisted(pkg) == true) {
                        Log.w(TAG, "getRootSafe: Private Mode active for $pkg. Observation blocked.")
                        return null
                    }
                }
                return root
            }
            if (attempt < MAX_ROOT_RETRIES - 1) {
                Thread.sleep(ROOT_RETRY_DELAY_MS)
            }
        }
        Log.w(TAG, "getRootSafe: failed after $MAX_ROOT_RETRIES retries")
        return null
    }

    /**
     * Recursively compacts the UI tree:
     * - Removes empty containers (no text, not interactive, no useful children)
     * - Keeps only interactive elements and visible text labels
     * This reduces token usage by ~70%
     */
    private fun compactNode(node: AccessibilityNodeInfo): UiNode? {
        val hasText = !node.text.isNullOrBlank()
        val hasDesc = !node.contentDescription.isNullOrBlank()
        val isInteractive = node.isClickable || node.isEditable || node.isScrollable || node.isCheckable

        // Process children recursively
        val compactedChildren = mutableListOf<UiNode>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val compactedChild = compactNode(child)
            if (compactedChild != null) {
                compactedChildren.add(compactedChild)
            }
        }

        // Skip this node if it has no text, is not interactive, and has no useful children
        if (!hasText && !hasDesc && !isInteractive && compactedChildren.isEmpty()) {
            return null
        }

        // If this node is just a wrapper with a single child and no own info, flatten it
        if (!hasText && !hasDesc && !isInteractive && compactedChildren.size == 1) {
            return compactedChildren[0]
        }

        val id = "node_${nodeCounter++}"
        nodeMap[id] = node

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        return UiNode(
            id = id,
            className = node.className?.toString()?.substringAfterLast('.') ?: "Unknown",
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            isClickable = node.isClickable,
            isEditable = node.isEditable,
            isScrollable = node.isScrollable,
            isCheckable = node.isCheckable,
            isChecked = node.isChecked,
            bounds = "[${rect.left},${rect.top}][${rect.right},${rect.bottom}]",
            children = compactedChildren
        )
    }

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val root = getRootSafe() ?: return null
        return findNode(root) { it.isEditable && it.isFocused } ?: findNode(root) { it.isEditable }
    }

    private fun findScrollableNode(): AccessibilityNodeInfo? {
        val root = getRootSafe() ?: return null
        return findNode(root) { it.isScrollable }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun tapAtNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val x = rect.centerX().toFloat()
        val y = rect.centerY().toFloat()

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        return dispatchGesture(gesture, null, null)
    }
}
