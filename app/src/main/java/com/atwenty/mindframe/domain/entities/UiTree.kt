package com.atwenty.mindframe.domain.entities

// --- UI Node for Compacted Tree ---

data class UiNode(
    val id: String,      // e.g., "node_5"
    val className: String,
    val text: String?,
    val contentDescription: String?,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val bounds: String,  // e.g., "[0,0][1080,200]"
    val children: List<UiNode> = emptyList()
) {
    fun toCompactString(indent: Int = 0): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        val attrs = mutableListOf<String>()

        if (isClickable) attrs.add("clickable")
        if (isEditable) attrs.add("editable")
        if (isScrollable) attrs.add("scrollable")
        if (isCheckable) attrs.add("checkable${if (isChecked) ":checked" else ":unchecked"}")

        sb.append("$prefix[$id] $className")
        if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
        if (!contentDescription.isNullOrBlank()) sb.append(" desc=\"$contentDescription\"")
        if (attrs.isNotEmpty()) sb.append(" {${attrs.joinToString(",")}}")
        sb.append(" $bounds")
        sb.appendLine()

        children.forEach { sb.append(it.toCompactString(indent + 1)) }
        return sb.toString()
    }
}
