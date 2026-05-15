package com.atwenty.mindframe.service.accessibility

import com.atwenty.mindframe.domain.entities.UiNode

/**
 * High-level contract for interacting with the device UI.
 * Decouples all skills and the orchestrator from the actual AccessibilityService.
 */
interface AccessibilityDriver {

    /** Returns the compacted UI tree of the current screen as a list of UiNodes. */
    fun readScreen(): List<UiNode>

    /** Returns the compacted UI tree as a formatted string for LLM consumption. */
    fun readScreenAsText(): String

    /** Clicks on the UI node matching the given ID (e.g., "node_5"). Returns true if successful. */
    fun clickNode(nodeId: String): Boolean

    /** Types text into the currently focused editable field, or the node identified by nodeId. */
    fun typeText(text: String, nodeId: String? = null): Boolean

    /** Scrolls in the given direction. direction: "up", "down", "left", "right". */
    fun scroll(direction: String, nodeId: String? = null): Boolean

    /** Presses the system back button. */
    fun pressBack(): Boolean

    /** Presses the system home button. */
    fun pressHome(): Boolean

    /** Presses the system recents button. */
    fun pressRecents(): Boolean

    /** Dismisses the soft keyboard if visible. */
    fun hideKeyboard(): Boolean

    /** Takes a screenshot and returns it as a Base64-encoded string, downscaled for LLM. */
    suspend fun takeScreenshot(): String?

    /** Returns the package name of the currently focused window. */
    fun getCurrentPackage(): String?

    /** Returns true if the accessibility service is connected and ready. */
    fun isReady(): Boolean
}
