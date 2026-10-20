package com.topjohnwu.magisk.ui.webui

/** Window insets in CSS pixels, exposed to module web pages as `internal/insets.css`. */
data class Insets(val top: Int, val bottom: Int, val left: Int, val right: Int) {

    val css: String
        get() = buildString {
            appendLine(":root {")
            appendLine("\t--safe-area-inset-top: ${top}px;")
            appendLine("\t--safe-area-inset-right: ${right}px;")
            appendLine("\t--safe-area-inset-bottom: ${bottom}px;")
            appendLine("\t--safe-area-inset-left: ${left}px;")
            appendLine("\t--window-inset-top: var(--safe-area-inset-top, 0px);")
            appendLine("\t--window-inset-bottom: var(--safe-area-inset-bottom, 0px);")
            appendLine("\t--window-inset-left: var(--safe-area-inset-left, 0px);")
            appendLine("\t--window-inset-right: var(--safe-area-inset-right, 0px);")
            appendLine("}")
        }

    companion object {
        val ZERO = Insets(0, 0, 0, 0)
    }
}
