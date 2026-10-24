package org.readerka.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.security.MessageDigest

/** Password bytes go directly to Security.framework, never through a shell or process arguments. */
internal class MacKeychainAccountStore(directory: File, private val keychain: Pointer? = null) {
    private val service = "org.readerka.dropbox".toByteArray(Charsets.UTF_8)
    private val account = MessageDigest.getInstance("SHA-256")
        .digest(directory.canonicalPath.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)

    fun exists(): Boolean = find { it != null }

    fun load(): String {
        val length = IntByReference()
        val password = PointerByReference()
        val item = PointerByReference()
        checkStatus(api.SecKeychainFindGenericPassword(keychain, service.size, service, account.size, account, length, password, item))
        try { return password.value.getByteArray(0, length.value).toString(Charsets.UTF_8) }
        finally {
            api.SecKeychainItemFreeContent(null, password.value)
            release(item.value)
        }
    }

    fun save(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            find { item ->
                checkStatus(if (item == null)
                    api.SecKeychainAddGenericPassword(keychain, service.size, service, account.size, account, bytes.size, bytes, null)
                else api.SecKeychainItemModifyAttributesAndData(item, null, bytes.size, bytes))
            }
        } finally { bytes.fill(0) }
    }

    fun forget() = find { item -> if (item != null) checkStatus(api.SecKeychainItemDelete(item)) }

    private fun <T> find(action: (Pointer?) -> T): T {
        val item = PointerByReference()
        val status = api.SecKeychainFindGenericPassword(keychain, service.size, service, account.size, account, null, null, item)
        if (status != -25300) checkStatus(status) // errSecItemNotFound is a normal disconnected state.
        try { return action(item.value) } finally { release(item.value) }
    }

    private fun checkStatus(status: Int) {
        check(status == 0) { "Не удалось обратиться к Связке ключей macOS (код $status). Разрешите Readerka доступ к сохранённому входу Dropbox." }
    }

    companion object {
        internal val api: MacSecurity by lazy { Native.load("Security", MacSecurity::class.java) }
        private val core: MacCoreFoundation by lazy { Native.load("CoreFoundation", MacCoreFoundation::class.java) }
        internal fun release(pointer: Pointer?) { if (pointer != null) core.CFRelease(pointer) }
    }
}

internal interface MacSecurity : Library {
    fun SecKeychainFindGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray, passwordLength: IntByReference?, password: PointerByReference?, item: PointerByReference): Int
    fun SecKeychainAddGenericPassword(keychain: Pointer?, serviceLength: Int, service: ByteArray, accountLength: Int, account: ByteArray, passwordLength: Int, password: ByteArray, item: PointerByReference?): Int
    fun SecKeychainItemModifyAttributesAndData(item: Pointer, attributes: Pointer?, length: Int, data: ByteArray): Int
    fun SecKeychainItemDelete(item: Pointer): Int
    fun SecKeychainItemFreeContent(attributes: Pointer?, data: Pointer): Int
    // Integration tests use a temporary keychain, never the user's login keychain.
    fun SecKeychainCreate(path: String, passwordLength: Int, password: ByteArray, promptUser: Byte, access: Pointer?, keychain: PointerByReference): Int
    fun SecKeychainDelete(keychain: Pointer): Int
}

internal interface MacCoreFoundation : Library { fun CFRelease(value: Pointer) }
