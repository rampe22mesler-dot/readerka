package org.readerka.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Per-library, per-Windows-user credentials; never included in library ZIPs. */
class DropboxAccountStore(directory: File) {
    private val file = File(directory, "dropbox-account.dpapi")
    fun exists() = file.isFile
    fun load(): DropboxCredentials {
        val decrypted = protect(file.readText(), false)
        return try { Json.decodeFromString(decrypted) }
        catch (_: IllegalArgumentException) { error("Сохранённый вход Dropbox повреждён. Отключите аккаунт и войдите снова.") }
    }
    fun save(credentials: DropboxCredentials) {
        val encrypted = protect(Json.encodeToString(credentials), true)
        val temp = Files.createTempFile(file.parentFile.toPath(), "dropbox-account-", ".tmp")
        try {
            Files.writeString(temp, encrypted)
            Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }
    fun forget() { Files.deleteIfExists(file.toPath()) }

    private fun protect(value: String, encrypt: Boolean): String {
        check(System.getProperty("os.name").startsWith("Windows")) { "Сохранение входа Dropbox поддерживается только в Windows." }
        val operation = if (encrypt) "Protect" else "Unprotect"
        val script = """
            ${'$'}ErrorActionPreference='Stop'
            Add-Type -AssemblyName System.Security
            try {
                ${'$'}bytes=[Convert]::FromBase64String([Console]::ReadLine())
                ${'$'}result=[Security.Cryptography.ProtectedData]::$operation(${'$'}bytes,${'$'}null,[Security.Cryptography.DataProtectionScope]::CurrentUser)
                [Console]::Write([Convert]::ToBase64String(${'$'}result))
            } catch { exit 1 }
        """.trimIndent()
        val command = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val executable = File(System.getenv("SystemRoot") ?: "C:\\Windows", "System32/WindowsPowerShell/v1.0/powershell.exe")
        val process = ProcessBuilder(executable.path, "-NoLogo", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", command)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            val result = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader(Charsets.US_ASCII).use { it.readText().trim() }
            }
            val input = if (encrypt) Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) else value
            process.outputStream.bufferedWriter(Charsets.US_ASCII).use { it.write(input); it.newLine() }
            check(process.waitFor(20, TimeUnit.SECONDS)) { "Windows не ответила при защите входа Dropbox." }
            check(process.exitValue() == 0) { "Не удалось прочитать или защитить вход Dropbox для этого пользователя Windows. Отключите аккаунт и войдите снова." }
            val output = result.get(5, TimeUnit.SECONDS)
            require(output.isNotEmpty()) { "Windows вернула пустые данные входа Dropbox." }
            return if (encrypt) output else Base64.getDecoder().decode(output).toString(Charsets.UTF_8)
        } finally { if (process.isAlive) process.destroyForcibly() }
    }
}
