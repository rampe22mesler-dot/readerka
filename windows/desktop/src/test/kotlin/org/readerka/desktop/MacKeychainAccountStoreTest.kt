package org.readerka.desktop

import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class MacKeychainAccountStoreTest {
    @Test fun credentialsRoundTripUpdateAndForgetInAnIsolatedKeychain() {
        assumeTrue(DesktopPlatform.isMac)
        val directory = Files.createTempDirectory("readerka-keychain-test-").toFile()
        val handle = PointerByReference()
        val api = MacKeychainAccountStore.api
        val password = "temporary-test-keychain".toByteArray()
        try {
            assertEquals(0, api.SecKeychainCreate(File(directory, "test.keychain").path, password.size, password, 0, null, handle))
            val store = MacKeychainAccountStore(File(directory, "library"), handle.value)
            val other = MacKeychainAccountStore(File(directory, "other-library"), handle.value)
            assertFalse(store.exists())
            store.save("секрет Dropbox — первый")
            assertTrue(store.exists())
            assertEquals("секрет Dropbox — первый", store.load())
            assertFalse(other.exists())
            store.save("updated-refresh-token")
            assertEquals("updated-refresh-token", store.load())
            store.forget()
            store.forget()
            assertFalse(store.exists())
        } finally {
            if (handle.value != null) {
                api.SecKeychainDelete(handle.value)
                MacKeychainAccountStore.release(handle.value)
            }
            directory.deleteRecursively()
        }
    }
}
