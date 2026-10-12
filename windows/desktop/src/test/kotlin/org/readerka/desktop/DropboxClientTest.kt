package org.readerka.desktop

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.ImportPolicy

class DropboxClientTest {
    private data class Reply(val path: String, val body: ByteArray = "{}".toByteArray(), val status: Int = 200, val headers: Map<String, String> = emptyMap())
    private data class Call(val path: String, val body: ByteArray, val auth: String?, val arg: JsonObject?)
    private class Server : AutoCloseable {
        val replies = LinkedBlockingQueue<Reply>()
        val calls = LinkedBlockingQueue<Call>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val reply = replies.poll()
                calls.add(Call(exchange.requestURI.path, exchange.requestBody.use { it.readBytes() }, exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("Dropbox-API-Arg")?.let { Json.parseToJsonElement(it).jsonObject }))
                val valid = reply != null && reply.path == exchange.requestURI.path
                reply?.headers?.forEach { (k, v) -> exchange.responseHeaders.set(k, v) }
                val body = if (valid) reply!!.body else "Unexpected request".toByteArray()
                exchange.sendResponseHeaders(if (valid) reply!!.status else 500, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val client = DropboxClient(base, base, 16)
        fun reply(path: String, body: String, status: Int = 200) { replies.add(Reply(path, body.toByteArray(), status)) }
        override fun close() { server.stop(0) }
    }
    private fun metadata(hash: String, size: Long, rev: String = "a123456789", tag: Boolean = true) =
        """{${if (tag) "\".tag\":\"file\"," else ""}"rev":"$rev","size":$size,"server_modified":"2026-10-12T10:00:00Z","content_hash":"$hash"}"""
    private fun fields(bytes: ByteArray) = bytes.toString(Charsets.UTF_8).split('&').associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
    }
    private fun expectFailure(block: () -> Unit): Exception {
        try { block() } catch (e: Exception) { return e }
        throw AssertionError("Expected failure")
    }

    @Test fun pkceLoginAndRefreshUseAppKeyWithoutSecret() = Server().use { server ->
        val attempt = DropboxClient.Login("exampleKey123")
        val query = fields(attempt.authorization.rawQuery.toByteArray())
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(attempt.verifier.toByteArray())), query["code_challenge"])
        assertEquals("offline", query["token_access_type"])
        assertFalse(query.containsKey("redirect_uri")) // Dropbox displays a one-time code, bound to this verifier.
        server.reply("/oauth2/token", """{"access_token":"access","refresh_token":"refresh"}""")
        server.reply("/2/users/get_current_account", """{"email":"reader@example.org"}""")
        val credentials = server.client.login(attempt, "one-time-code")
        assertEquals("reader@example.org", credentials.account)
        val exchange = fields(server.calls.remove().body)
        assertEquals(attempt.verifier, exchange["code_verifier"])
        assertFalse(exchange.containsKey("client_secret"))
        assertEquals("Bearer access", server.calls.remove().auth)
        server.reply("/oauth2/token", """{"access_token":"renewed"}""")
        assertEquals("renewed", server.client.access(credentials))
        val refresh = fields(server.calls.remove().body)
        assertEquals("refresh_token", refresh["grant_type"])
        assertEquals("refresh", refresh["refresh_token"])
        assertFalse(refresh.containsKey("client_secret"))
    }

    @Test fun fullLibraryStreamsInChunksAndDownloadsIntoExistingImportPipeline() = Server().use { server ->
        val root = Files.createTempDirectory("readerka-dropbox-transfer").toFile()
        try {
            DesktopRepository(File(root, "source")).use { source ->
                source.persist(LibrarySnapshot(entries = listOf(Entry("cloud-entry", "Из Dropbox"))))
                source.persistPreference("dropbox-app-key", "must-not-export")
                File(source.directory, "dropbox-account.dpapi").writeText("must-not-export")
                val archive = File(root, "snapshot.zip")
                source.export(archive)
                val hash = server.client.dropboxHash(archive)
                val info = metadata(hash, archive.length(), tag = false)
                server.reply("/2/files/upload_session/start", """{"session_id":"session"}""")
                var offset = 16L
                while (archive.length() - offset > 16) {
                    server.reply("/2/files/upload_session/append_v2", "null")
                    offset += 16
                }
                server.reply("/2/files/upload_session/finish", info)
                val uploaded = server.client.upload("access", archive, null)
                val calls = server.calls.toList()
                assertArrayEquals(archive.readBytes(), calls.flatMap { it.body.toList() }.toByteArray())
                assertTrue(calls.all { it.auth == "Bearer access" && it.body.size <= 16 })
                var seen = calls.first().body.size.toLong()
                calls.drop(1).forEach {
                    assertEquals(seen, it.arg!!.getValue("cursor").jsonObject.getValue("offset").jsonPrimitive.long)
                    seen += it.body.size
                }
                val commit = calls.last().arg!!.getValue("commit").jsonObject
                assertEquals("add", commit.getValue("mode").jsonPrimitive.content)
                assertTrue(commit.getValue("strict_conflict").jsonPrimitive.boolean)
                assertFalse(commit.getValue("autorename").jsonPrimitive.boolean)
                server.replies.add(Reply("/2/files/download", archive.readBytes(), headers = mapOf("Dropbox-API-Result" to info)))
                val downloaded = File(root, "downloaded.zip")
                server.client.download("access", uploaded, downloaded)
                assertArrayEquals(archive.readBytes(), downloaded.readBytes())
                DesktopRepository(File(root, "destination")).use { destination ->
                    destination.persist(LibrarySnapshot(entries = listOf(Entry("local-entry", "На устройстве"))))
                    val staged = destination.stage(downloaded)
                    val backup = destination.applyImport(staged, ImportPolicy.KeepLocal)
                    assertTrue(backup.isFile)
                    assertEquals(setOf("local-entry", "cloud-entry"), destination.snapshot().entries.map { it.id }.toSet())
                    assertNull(destination.preference("dropbox-app-key"))
                    assertFalse(File(destination.directory, "dropbox-account.dpapi").exists())
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun overwriteIsConditionalAndConflictStopsUpload() = Server().use { server ->
        val archive = Files.createTempFile("readerka-dropbox", ".zip").toFile()
        try {
            archive.writeBytes(ByteArray(18) { it.toByte() })
            server.reply("/2/files/upload_session/start", """{"session_id":"s"}""")
            server.reply("/2/files/upload_session/finish", """{"error_summary":"path/conflict/file/"}""", 409)
            val error = expectFailure { server.client.upload("access", archive, DropboxFile("old-revision", 18, "date", "hash")) }
            assertTrue(error is DropboxException)
            assertTrue(error.message!!.contains("Конфликт"))
            val mode = server.calls.toList().last().arg!!.getValue("commit").jsonObject.getValue("mode").jsonObject
            assertEquals("update", mode.getValue(".tag").jsonPrimitive.content)
            assertEquals("old-revision", mode.getValue("update").jsonPrimitive.content)
        } finally { archive.delete() }
    }

    @Test fun onlyMissingPathCountsAsMissingAndErrorsDoNotExposeResponses() = Server().use { server ->
        server.reply("/2/files/get_metadata", """{"error_summary":"path/not_found/"}""", 409)
        assertNull(server.client.metadata("access"))
        server.reply("/2/files/get_metadata", """{"error_summary":"path/restricted_content/"}""", 409)
        assertTrue(expectFailure { server.client.metadata("access") } is DropboxException)
        server.reply("/oauth2/token", """{"error":"invalid_grant","secret":"sensitive-refresh-token"}""", 400)
        val error = expectFailure { server.client.access(DropboxCredentials("key", "refresh", "email")) }
        assertFalse(error.toString().contains("sensitive-refresh-token"))
        server.reply("/oauth2/token", """{"access_token":"sensitive-access-token",BROKEN""")
        val malformed = expectFailure { server.client.access(DropboxCredentials("key", "refresh", "email")) }
        assertFalse(malformed.toString().contains("sensitive-access-token"))
        assertNull(malformed.cause)
        server.reply("/2/files/get_metadata", """{".tag":"folder","name":"Readerka-library.zip"}""")
        assertTrue(expectFailure { server.client.metadata("access") }.message!!.contains("папка"))
    }

    @Test fun failedAndMismatchedDownloadsLeaveNoFile() = Server().use { server ->
        val root = Files.createTempDirectory("readerka-dropbox-failed").toFile()
        try {
            val target = File(root, "download.zip")
            val remote = DropboxFile("a123456789", 5, "date", "invalid-hash")
            server.replies.add(Reply("/2/files/download", ByteArray(5), headers = mapOf("Dropbox-API-Result" to metadata(remote.contentHash, remote.size))))
            expectFailure { server.client.download("access", remote, target) }
            assertFalse(target.exists())
            server.replies.add(Reply("/2/files/download", ByteArray(7), headers = mapOf("Dropbox-API-Result" to metadata(remote.contentHash, remote.size))))
            expectFailure { server.client.download("access", remote, target) }
            assertFalse(target.exists())
            server.replies.add(Reply("/2/files/download", ByteArray(5), headers = mapOf("Dropbox-API-Result" to metadata(remote.contentHash, remote.size, "b123456789"))))
            expectFailure { server.client.download("access", remote, target) }
            assertFalse(target.exists())
            expectFailure { server.client.download("access", remote.copy(size = DropboxClient.MAX_ARCHIVE + 1), target) }
            assertTrue(server.replies.isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun windowsProtectsCredentialsAndExportExcludesThem() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = Files.createTempDirectory("readerka-dropbox-credentials").toFile()
        try {
            val store = DropboxAccountStore(root)
            val credentials = DropboxCredentials("exampleKey123", "private-refresh-token", "reader@example.org")
            assertFalse(store.exists())
            store.save(credentials)
            assertFalse(File(root, "dropbox-account.dpapi").readText().contains(credentials.refreshToken))
            assertEquals(credentials, store.load())
            store.forget()
            assertFalse(store.exists())
        } finally { root.deleteRecursively() }
    }
}
