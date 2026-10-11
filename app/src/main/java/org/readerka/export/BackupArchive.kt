package org.readerka.export

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.readerka.data.*
import java.io.*
import java.security.MessageDigest
import java.util.zip.*

@Serializable
data class BackupManifest(val version: Int = 4, val sha256: Map<String, String>)
data class StagedBackup(val data: LibrarySnapshot, val directory: File)

object BackupArchive {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private fun digest(file: File) = file.inputStream().use { input ->
        val md = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; md.update(buffer, 0, n) }
        md.digest().joinToString("") { "%02x".format(it) }
    }
    fun write(output: OutputStream, data: LibrarySnapshot, pdf: (Document) -> File) {
        validate(data)
        val bytes = json.encodeToString(data).toByteArray()
        val hashes = linkedMapOf("library.json" to MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        for (d in data.documents) { val file = pdf(d); require(file.isFile) { "Missing PDF: ${d.originalName}" }; hashes["pdfs/${d.storedName}"] = digest(file) }
        ZipOutputStream(output.buffered()).use { zip ->
            fun put(path: String, write: () -> Unit) { zip.putNextEntry(ZipEntry(path)); write(); zip.closeEntry() }
            put("manifest.json") { zip.write(json.encodeToString(BackupManifest(version = data.version, sha256 = hashes)).toByteArray()) }
            put("library.json") { zip.write(bytes) }
            for (d in data.documents) put("pdfs/${d.storedName}") { pdf(d).inputStream().use { it.copyTo(zip) } }
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
                    require(seen.size < 50_000 && seen.add(e.name)) { "Too many or duplicate archive entries" }
                    require(e.name in listOf("manifest.json", "library.json") || e.name.matches(Regex("pdfs/[A-Za-z0-9_-]+\\.pdf"))) { "Unsafe archive path" }
                    require(!e.isDirectory)
                    val file = File(directory, e.name); file.parentFile!!.mkdirs()
                    var size = 0L
                    file.outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) { val n = zip.read(buffer); if (n < 0) break; size += n; total += n
                            require(total <= 2L * 1024 * 1024 * 1024 && size <= if (e.name.endsWith(".json")) 16L * 1024 * 1024 else 1024L * 1024 * 1024) { "Backup exceeds supported size" }
                            out.write(buffer, 0, n)
                        }
                    }
                }
            }
            val manifest = json.decodeFromString<BackupManifest>(File(directory, "manifest.json").readText())
            require(manifest.version in 1..4) { "Unsupported backup version" }
            require(seen == manifest.sha256.keys + "manifest.json") { "Incomplete backup" }
            for ((name, hash) in manifest.sha256) require(digest(File(directory, name)) == hash) { "Checksum mismatch" }
            val data = json.decodeFromString<LibrarySnapshot>(File(directory, "library.json").readText())
            validate(data)
            require(manifest.sha256.keys == data.documents.map { "pdfs/${it.storedName}" }.toSet() + "library.json") { "PDF manifest mismatch" }
            var separated = data.separateLegacyCollections()
            if (data.version < 4) separated = separated.copy(noteVersions = data.noteVersions.map { v ->
                val draft = json.decodeFromString<EntryDraft>(v.snapshot)
                v.copy(snapshot = json.encodeToString(draft.copy(collections = scopedCollectionIds(draft.collections, draft.entry.kind, separated.collections))))
            })
            validate(separated)
            return StagedBackup(separated, directory)
        } catch (e: Exception) { directory.deleteRecursively(); throw e }
    }
    fun validate(s: LibrarySnapshot) {
        require(s.version in 1..4)
        fun ids(values: List<String>): Set<String> { require(values.all { it.isNotBlank() } && values.distinct().size == values.size); return values.toSet() }
        val docIds = ids(s.documents.map { it.id }); val entryIds = ids(s.entries.map { it.id })
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
        s.documents.forEach { require(it.storedName.matches(Regex("[A-Za-z0-9_-]+\\.pdf")) && (it.folderId == null || it.folderId in folderIds) && it.lastPage >= 0 && it.pageCount >= 0) }
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
