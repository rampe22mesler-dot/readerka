package org.readerka.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.readerka.data.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

@Serializable
data class BackupManifest(val version: Int = 4, val sha256: Map<String, String>)
data class StagedBackup(val data: LibrarySnapshot, val directory: File)

object BackupArchive {
    private const val MAX_ENTRIES = 50_000
    private const val MAX_JSON = 64L * 1024 * 1024
    private const val MAX_PDF = 1024L * 1024 * 1024
    private const val MAX_TOTAL = 2L * 1024 * 1024 * 1024
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private fun digest(file: File) = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    fun write(output: OutputStream, data: LibrarySnapshot, pdf: (Document) -> File) {
        validate(data)
        require(data.documents.size <= MAX_ENTRIES - 2) { "Too many archive entries" }
        // Rollback archives must be readable by the preceding strict JSON decoder.
        val encoded = json.encodeToJsonElement(data).jsonObject
        val bytes = (if (data.version < 5) JsonObject(encoded.entries.filter { it.key != "researchFields" }.associate { it.toPair() }).toString()
            else json.encodeToString(data)).toByteArray()
        require(bytes.size.toLong() <= MAX_JSON) { "Backup exceeds supported size" }
        val hashes = linkedMapOf("library.json" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val files = data.documents.associate { it.storedName to pdf(it) }
        var total = bytes.size.toLong()
        for (d in data.documents) {
            val file = files.getValue(d.storedName)
            require(file.isFile) { "Missing PDF: ${d.originalName}" }
            total += file.length()
            require(file.length() <= MAX_PDF && total <= MAX_TOTAL) { "Backup exceeds supported size" }
            hashes["pdfs/${d.storedName}"] = digest(file)
        }
        val manifest = json.encodeToString(BackupManifest(version = data.version, sha256 = hashes)).toByteArray()
        require(manifest.size.toLong() <= MAX_JSON && total + manifest.size <= MAX_TOTAL) { "Backup exceeds supported size" }
        total = bytes.size.toLong() + manifest.size
        ZipOutputStream(output.buffered()).use { zip ->
            fun put(path: String, write: () -> Unit) { zip.putNextEntry(ZipEntry(path)); write(); zip.closeEntry() }
            put("manifest.json") { zip.write(manifest) }
            put("library.json") { zip.write(bytes) }
            for (d in data.documents) put("pdfs/${d.storedName}") {
                files.getValue(d.storedName).inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024); var size = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        size += n; total += n
                        require(size <= MAX_PDF && total <= MAX_TOTAL) { "Backup exceeds supported size" }
                        zip.write(buffer, 0, n)
                    }
                }
            }
        }
    }
    /** Unpack and fully verify outside live storage; no live database or PDF is touched here. */
    fun stage(input: InputStream, directory: File): StagedBackup {
        require(directory.mkdir()) { "Staging directory already exists" }
        try {
            val seen = mutableSetOf<String>(); var total = 0L
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    require(seen.size < MAX_ENTRIES && seen.add(e.name)) { "Too many or duplicate archive entries" }
                    require(e.name in listOf("manifest.json", "library.json") || e.name.matches(Regex("pdfs/[A-Za-z0-9_-]+\\.pdf"))) { "Unsafe archive path" }
                    require(!e.isDirectory)
                    val file = File(directory, e.name); file.parentFile!!.mkdirs()
                    var size = 0L
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = zip.read(buffer); if (n < 0) break; size += n; total += n
                            require(total <= MAX_TOTAL && size <= if (e.name.endsWith(".json")) MAX_JSON else MAX_PDF) { "Backup exceeds supported size" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val manifest = json.decodeFromString<BackupManifest>(File(directory, "manifest.json").readText())
            require(manifest.version in 1..5) { "Unsupported backup version" }
            require(seen == manifest.sha256.keys + "manifest.json") { "Incomplete backup" }
            for ((name, hash) in manifest.sha256) require(digest(File(directory, name)) == hash) { "Checksum mismatch" }
            val data = json.decodeFromString<LibrarySnapshot>(File(directory, "library.json").readText())
            require(data.version == manifest.version) { "Library and manifest versions differ" }
            validate(data)
            require(manifest.sha256.keys == data.documents.map { "pdfs/${it.storedName}" }.toSet() + "library.json") { "PDF manifest mismatch" }
            var separated = data.separateLegacyCollections()
            if (data.version < 4) separated = separated.copy(noteVersions = data.noteVersions.map { v ->
                val draft = json.decodeFromString<EntryDraft>(v.snapshot)
                v.copy(snapshot = json.encodeToString(draft.copy(collections = scopedCollectionIds(draft.collections, draft.entry.kind, separated.collections))))
            })
            separated = separated.migrateResearchFields()
            validate(separated)
            return StagedBackup(separated, directory)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    fun validate(s: LibrarySnapshot) {
        require(s.version in 1..5)
        require(s.version >= 5 || s.researchFields.isEmpty())
        fun ids(values: List<String>): Set<String> { require(values.all { it.isNotBlank() } && values.distinct().size == values.size); return values.toSet() }
        val docIds = ids(s.documents.map { it.id }); val entryIds = ids(s.entries.map { it.id })
        ids(s.researchFields.map { it.id })
        s.researchFields.forEach { it.validate(entryIds) }
        val collectionIds = ids(s.collections.map { it.id }); val folderIds = ids(s.documentFolders.map { it.id }); val tagIds = ids(s.tags.map { it.id })
        ids(s.bookmarks.map { it.id })
        ids(s.readerFolders.map { it.id })
        ReaderStructure.validate(s.readerFolders, s.readerMemberships, s.bookmarks, s.entries, docIds)
        require(s.documents.map { it.storedName }.distinct().size == s.documents.size)
        require(s.tags.map { it.name }.distinct().size == s.tags.size)
        FolderTree.validate(s.documentFolders.map { it.id to it.parentId }); FolderTree.validate(s.collections.map { it.id to it.parentId })
        if (s.version >= 4) {
            val folders = s.collections.associateBy { it.id }
            val entries = s.entries.associateBy { it.id }
            s.collections.forEach { c -> require(c.parentId == null || folders[c.parentId]?.scope == c.scope) }
            s.memberships.forEach { m -> require(folders[m.collectionId]?.scope == entries[m.entryId]?.kind?.collectionScope()) }
        }
        s.documents.forEach {
            require(it.storedName.matches(Regex("[A-Za-z0-9_-]+\\.pdf")) && (it.folderId == null || it.folderId in folderIds) && it.lastPage >= 0 && it.pageCount >= 0)
            require(it.pageCount == 0 || it.lastPage < it.pageCount) { "Invalid remembered PDF page" }
            require(it.lastOffset in 0..10_000) { "Invalid PDF scroll offset" }
        }
        s.entries.forEach { e ->
            require(e.documentId == null || e.documentId in docIds)
            e.anchor?.let { a ->
                val doc = s.documents.firstOrNull { it.id == e.documentId } ?: error("Missing source")
                require(a.page >= 0 && a.page < doc.pageCount && a.start >= 0 && a.end > a.start && a.rects.isNotEmpty())
                for (r in a.rects) require(r.left.isFinite() && r.top.isFinite() && r.right.isFinite() && r.bottom.isFinite() && r.left in 0f..1f && r.top in 0f..1f && r.right in r.left..1f && r.bottom in r.top..1f)
            }
        }
        ids(s.noteVersions.map { it.id })
        s.entries.forEach { e ->
            require(e.sources.distinct().size == e.sources.size && e.sources.all { it in docIds })
            e.content?.let { c ->
                require(c.marks.all { it.start >= 0 && it.end <= c.text.length && it.start < it.end })
                require(c.blocks.map { it.id }.distinct().size == c.blocks.size && c.blocks.firstOrNull()?.start == 0)
                require(c.blocks.map { it.start } == (listOf(0) + c.text.indices.filter { c.text[it] == '\n' }.map { it + 1 }))
                require(c.markdown() == e.comment)
            }
        }
        s.noteVersions.forEach { v ->
            require(v.entryId in entryIds)
            val draft = json.decodeFromString<EntryDraft>(v.snapshot)
            require(draft.entry.id == v.entryId)
        }
        s.memberships.forEach { require(it.entryId in entryIds && it.collectionId in collectionIds) }
        s.entryTags.forEach { require(it.entryId in entryIds && it.tagId in tagIds) }
        s.documentTags.forEach { require(it.documentId in docIds && it.tagId in tagIds) }
        s.bookmarks.forEach { b -> require(b.documentId in docIds && b.page >= 0 && b.page < s.documents.first { it.id == b.documentId }.pageCount) }
    }
}
