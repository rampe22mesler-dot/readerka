package org.readerka.desktop

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class DesktopPlatformTest {
    @Test fun macLibraryUsesApplicationSupportEvenWhenOtherPlatformVariablesExist() {
        assertEquals(File("/Users/reader/Library/Application Support/Readerka"), DesktopPlatform.dataDirectory(
            "Mac OS X", "/Users/reader", mapOf("LOCALAPPDATA" to "/wrong", "XDG_DATA_HOME" to "/also-wrong")))
    }

    @Test fun windowsAndLinuxKeepTheirExistingLibraries() {
        assertEquals(File("C:/Users/reader/AppData/Local/Readerka"), DesktopPlatform.dataDirectory(
            "Windows 11", "C:/Users/reader", mapOf("LOCALAPPDATA" to "C:/Users/reader/AppData/Local")))
        assertEquals(File("/home/reader/.local/share/readerka"), DesktopPlatform.dataDirectory("Linux", "/home/reader", emptyMap()))
        assertEquals(File("/custom/readerka"), DesktopPlatform.dataDirectory("Linux", "/home/reader", mapOf("XDG_DATA_HOME" to "/custom")))
    }

    @Test fun macShortcutsUseCommandAndKeepOtherModifiersAndNavigationKeys() {
        assertEquals("meta shift S", DesktopPlatform.shortcutKey("control shift S", true))
        assertEquals("meta alt L", DesktopPlatform.shortcutKey("control alt L", true))
        assertEquals("alt LEFT", DesktopPlatform.shortcutKey("alt LEFT", true))
        assertEquals("control S", DesktopPlatform.shortcutKey("control S", false))
    }
}
