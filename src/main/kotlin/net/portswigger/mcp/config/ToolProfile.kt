package net.portswigger.mcp.config

enum class ToolProfile(val displayName: String, val description: String) {
    CORE("Core (recommended)", "Compact catalog with modern, non-redundant Burp workflows."),
    FULL("Full compatibility", "All tools, including legacy and overlapping compatibility tools.");

    override fun toString(): String = displayName

    companion object {
        fun parse(value: String?): ToolProfile = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        } ?: CORE
    }
}
