package com.chuckiehelper.mobile.nativeui

private val pwshCommandPrefix = Regex(
    """^\s*(?:"([^"]+)"|'([^']+)'|(\S+))\s+(?:(?:-(?:NoLogo|NoProfile|NonInteractive)\s+)|(?:-ExecutionPolicy\s+\S+\s+))*-(?:Command|c)\s+([\s\S]+)$""",
    RegexOption.IGNORE_CASE,
)

/** Shorten only the display wrapper; never rewrite commands sent to the agent. */
internal fun compactCodexToolPreview(preview: String): String {
    val match = pwshCommandPrefix.matchEntire(preview) ?: return preview
    val executable = match.groupValues.drop(1).take(3).firstOrNull { it.isNotEmpty() } ?: return preview
    val name = executable.replace('\\', '/').substringAfterLast('/').lowercase()
    if (name !in setOf("pwsh", "pwsh.exe")) return preview
    var command = match.groupValues[4].trim()
    if (command.firstOrNull() in listOf('\'', '"')) {
        val quote = command.first()
        command = command.drop(1)
        if (command.lastOrNull() == quote) command = command.dropLast(1)
    }
    return "pwsh $command"
}
