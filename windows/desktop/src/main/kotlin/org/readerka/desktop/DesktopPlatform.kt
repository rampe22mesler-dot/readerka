package org.readerka.desktop

import java.awt.event.InputEvent
import java.io.File
import javax.swing.KeyStroke

/** Platform choices shared by the launcher, storage and Swing actions. */
internal object DesktopPlatform {
    val isMac: Boolean get() = System.getProperty("os.name").startsWith("Mac")

    fun dataDirectory(
        os: String = System.getProperty("os.name"),
        home: String = System.getProperty("user.home"),
        environment: Map<String, String> = System.getenv(),
    ): File = when {
        os.startsWith("Mac") -> File(home, "Library/Application Support/Readerka")
        os.startsWith("Windows") -> File(environment["LOCALAPPDATA"] ?: "$home/AppData/Local", "Readerka")
        else -> File(environment["XDG_DATA_HOME"] ?: "$home/.local/share", "readerka")
    }

    fun keyStroke(key: String): KeyStroke = KeyStroke.getKeyStroke(shortcutKey(key, isMac))
    fun shortcutKey(key: String, mac: Boolean): String =
        if (mac) key.split(' ').joinToString(" ") { if (it == "control") "meta" else it } else key

    fun isShortcutDown(event: InputEvent): Boolean = if (isMac) event.isMetaDown else event.isControlDown
    fun shortcutText(text: String): String = if (isMac) text.replace("Ctrl", "⌘") else text
}
