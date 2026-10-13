package org.readerka

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.dropbox.DropboxAccountStore
import org.readerka.dropbox.DropboxCredentials
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidPlatformStorageTest {
    @Test fun keystoreCredentialsAreEncryptedAuthenticatedAndLocallyRemovable() {
        val context = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val name = "test-dropbox-${UUID.randomUUID()}"
        val alias = "Readerka.Test.$name"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val store = DropboxAccountStore(context, name, alias)
        val synthetic = DropboxCredentials("synthetic-app-key", "synthetic-refresh-token", "Synthetic account")
        try {
            assertNull(store.load())
            store.save(synthetic)
            val encoded = prefs.getString("encrypted", null)!!
            assertFalse(encoded.contains(synthetic.refreshToken))
            assertEquals(synthetic, DropboxAccountStore(context, name, alias).load())
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            prefs.edit().putString("encrypted", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)).commit()
            assertThrows(Exception::class.java) { store.load() }
            store.clear()
            assertNull(store.load())
        } finally {
            prefs.edit().clear().commit()
            context.deleteSharedPreferences(name)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
        }
    }
}
