package org.readerka.desktop

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
data class DropboxCredentials(val appKey: String, val refreshToken: String, val account: String)

data class DropboxFile(val revision: String, val size: Long, val modified: String, val contentHash: String)

/** No timers, watchers, automatic uploads, or redirects carrying bearer tokens. */
class DropboxClient internal constructor(
    private val api: String = "https://api.dropboxapi.com",
    private val content: String = "https://content.dropboxapi.com",
    private val chunkSize: Int = 8 * 1024 * 1024,
) {
    companion object {
        const val PATH = "/Readerka-library.zip"
        const val MAX_ARCHIVE = 3L * 1024 * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        internal fun form(values: Map<String, String>) = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
    }

    class Login(val appKey: String) {
        val verifier: String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(64).also { SecureRandom().nextBytes(it) })
        val authorization: URI = URI("https://www.dropbox.com/oauth2/authorize?" + form(mapOf(
            "client_id" to appKey, "response_type" to "code", "token_access_type" to "offline",
            "code_challenge_method" to "S256",
            "code_challenge" to Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))),
            "scope" to "account_info.read files.metadata.read files.content.read files.content.write",
        )))
        init { require(appKey.matches(Regex("[A-Za-z0-9_-]{5,128}"))) { "Укажите App key из Dropbox App Console." } }
    }

    fun login(attempt: Login, code: String): DropboxCredentials {
        require(code.isNotBlank()) { "Введите код, выданный Dropbox после входа." }
        val result = token(mapOf("grant_type" to "authorization_code", "client_id" to attempt.appKey,
            "code" to code.trim(), "code_verifier" to attempt.verifier))
        val access = result.string("access_token")
        val account = rpc("users/get_current_account", access, JsonNull)
        return DropboxCredentials(attempt.appKey, result.string("refresh_token"), account.string("email"))
    }

    fun access(credentials: DropboxCredentials): String = token(mapOf(
        "grant_type" to "refresh_token", "client_id" to credentials.appKey, "refresh_token" to credentials.refreshToken,
    )).string("access_token")

    fun revoke(access: String) { rpc("auth/token/revoke", access, JsonNull) }

    fun metadata(access: String): DropboxFile? {
        try {
            return file(rpc("files/get_metadata", access, buildJsonObject { put("path", PATH) }))
        } catch (e: DropboxException) {
            if (e.status == 409 && (e.tag == "path/not_found" || e.tag.startsWith("path/not_found/"))) return null
            throw e
        }
    }

    /** Revision conditions prevent a confirmed remote copy from being overwritten after another device changes it. */
    fun upload(access: String, archive: File, previous: DropboxFile?): DropboxFile {
        require(archive.isFile && archive.length() in 1..MAX_ARCHIVE) { "Недопустимый размер ZIP библиотеки." }
        val commit = buildJsonObject {
            put("path", PATH)
            put("mode", previous?.let { buildJsonObject { put(".tag", "update"); put("update", it.revision) } } ?: JsonPrimitive("add"))
            put("autorename", false); put("strict_conflict", true); put("mute", true)
        }
        archive.inputStream().use { input ->
            val first = input.readNBytes(chunkSize)
            val start = contentJson("files/upload_session/start", access, buildJsonObject { put("close", false) }, first)
            val session = start.string("session_id")
            var offset = first.size.toLong()
            while (archive.length() - offset > chunkSize) {
                val bytes = input.readNBytes(chunkSize)
                require(bytes.isNotEmpty()) { "ZIP изменился во время выгрузки." }
                contentJson("files/upload_session/append_v2", access, buildJsonObject {
                    put("cursor", cursor(session, offset)); put("close", false)
                }, bytes)
                offset += bytes.size
            }
            val last = input.readNBytes(chunkSize)
            require(offset + last.size == archive.length() && input.read() == -1) { "ZIP изменился во время выгрузки." }
            return file(contentJson("files/upload_session/finish", access, buildJsonObject {
                put("cursor", cursor(session, offset)); put("commit", commit)
            }, last))
        }
    }

    /** Stream a pinned revision; remove incomplete downloads on every failure. */
    fun download(access: String, remote: DropboxFile, target: File) {
        require(remote.size in 1..MAX_ARCHIVE) { "Размер облачного ZIP превышает допустимый предел (3 ГиБ)." }
        val connection = request(content + "/2/files/download", access, "application/octet-stream", ByteArray(0),
            buildJsonObject { put("path", PATH); put("rev", remote.revision) }.toString())
        try {
            checkResponse(connection)
            val received = file(json.parseToJsonElement(connection.getHeaderField("Dropbox-API-Result") ?: error("Dropbox не вернул описание файла.")).jsonObject)
            require(received.revision == remote.revision && received.size == remote.size && received.contentHash == remote.contentHash) {
                "Облачная копия изменилась. Повторите загрузку."
            }
            connection.inputStream.use { input -> target.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                var size = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= remote.size && size <= MAX_ARCHIVE) { "Dropbox передал ZIP неожиданного размера." }
                    output.write(buffer, 0, count)
                }
                require(size == remote.size) { "Загрузка ZIP прервалась. Повторите операцию." }
            } }
            require(dropboxHash(target) == remote.contentHash) { "Контрольная сумма облачного ZIP не совпала. Повторите загрузку." }
        } catch (e: Exception) {
            target.delete()
            throw e
        } finally { connection.disconnect() }
    }

    internal fun dropboxHash(file: File): String {
        val total = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            while (true) {
                val bytes = input.readNBytes(4 * 1024 * 1024)
                if (bytes.isEmpty()) break
                total.update(MessageDigest.getInstance("SHA-256").digest(bytes))
            }
        }
        return total.digest().joinToString("") { "%02x".format(it) }
    }

    private fun cursor(session: String, offset: Long) = buildJsonObject { put("session_id", session); put("offset", offset) }
    private fun file(value: JsonObject): DropboxFile {
        require(value[".tag"]?.jsonPrimitive?.content.let { it == null || it == "file" }) { "В Dropbox по пути $PATH находится папка, а не ZIP." }
        return DropboxFile(value.string("rev"), value.getValue("size").jsonPrimitive.long,
            value.string("server_modified"), value.string("content_hash"))
    }

    private fun token(values: Map<String, String>) = response(request(api + "/oauth2/token", null,
        "application/x-www-form-urlencoded", form(values).toByteArray(Charsets.UTF_8)))
    private fun rpc(route: String, access: String, body: JsonElement) = response(request(api + "/2/" + route, access,
        "application/json", body.toString().toByteArray(Charsets.UTF_8)))
    private fun contentJson(route: String, access: String, arg: JsonObject, bytes: ByteArray) =
        response(request(content + "/2/" + route, access, "application/octet-stream", bytes, arg.toString()))

    private fun request(url: String, access: String?, type: String, bytes: ByteArray, argument: String? = null): HttpURLConnection {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000; connection.readTimeout = 60000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", type)
            access?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            argument?.let { connection.setRequestProperty("Dropbox-API-Arg", it) }
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return connection
        } catch (e: Exception) { connection.disconnect(); throw e }
    }

    private fun response(connection: HttpURLConnection): JsonObject {
        try {
            checkResponse(connection)
            val bytes = connection.inputStream.use { it.readNBytes(1024 * 1024 + 1) }
            require(bytes.size <= 1024 * 1024) { "Слишком большой ответ Dropbox." }
            return if (bytes.isEmpty() || bytes.toString(Charsets.UTF_8) == "null") buildJsonObject {} else
                try { json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
                catch (_: IllegalArgumentException) { throw IOException("Dropbox вернул некорректный ответ.") }
        } finally { connection.disconnect() }
    }

    private fun checkResponse(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299) return
        val body = connection.errorStream?.use { it.readNBytes(64 * 1024).toString(Charsets.UTF_8) }.orEmpty()
        val tag = runCatching { json.parseToJsonElement(body).jsonObject["error_summary"]?.jsonPrimitive?.content?.trimEnd('/') }.getOrNull().orEmpty()
        // Never surface raw OAuth responses, authorization codes or bearer tokens in logs/UI.
        throw DropboxException(status, tag, when (status) {
            400 -> "Dropbox отклонил запрос. Проверьте App key, разрешения приложения и код входа."
            401 -> "Вход в Dropbox истёк или отозван. Отключите аккаунт и войдите снова."
            403 -> "Dropbox запретил операцию. Проверьте разрешения приложения и доступ к аккаунту."
            409 -> if (tag.startsWith("path/not_found")) "В Dropbox пока нет копии библиотеки. Сначала выполните выгрузку." else "Конфликт Dropbox: копия изменилась или выбранный путь занят. Повторите операцию."
            429 -> "Dropbox временно ограничил запросы. Повторите операцию позже."
            507 -> "В Dropbox недостаточно свободного места."
            else -> "Ошибка Dropbox (HTTP $status). Повторите операцию позже."
        })
    }
}

class DropboxException(val status: Int, val tag: String, message: String) : IOException(message)
private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content.also { require(it.isNotBlank()) { "Неполный ответ Dropbox." } }
