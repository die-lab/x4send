package dev.diegocarli.x4send

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Metadati minimi letti dal file .opf dentro l'EPUB (che e' uno zip).
 * La lettura si ferma appena trova l'OPF, quindi non scorre tutto il libro.
 */
data class EpubMeta(val title: String?, val author: String?, val authorSort: String?) {

    /** Nome cartella nello stile "Cognome, Nome", oppure null se l'autore non e' leggibile. */
    val authorFolder: String? get() = folderName(authorSort ?: author)

    private data class Creator(val id: String?, val text: String, val fileAs: String?)

    companion object {

        fun read(open: () -> InputStream): EpubMeta? {
            ZipInputStream(open().buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.endsWith(".opf", ignoreCase = true)) {
                        return parseOpf(zip.readBytes())
                    }
                    e = zip.nextEntry
                }
            }
            return null
        }

        private fun localName(tag: String) = tag.substringAfter(':')

        private fun parseOpf(bytes: ByteArray): EpubMeta? {
            return try {
                val p = Xml.newPullParser()
                p.setInput(bytes.inputStream(), null)

                var title: String? = null
                val creators = mutableListOf<Creator>()
                val fileAsByRef = mutableMapOf<String, String>()

                var ev = p.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG) {
                        when (localName(p.name)) {
                            "title" -> {
                                val t = p.nextText().trim()
                                if (title == null && t.isNotEmpty()) title = t
                            }
                            "creator" -> {
                                val id = p.getAttributeValue(null, "id")
                                var fileAs: String? = null
                                for (i in 0 until p.attributeCount) {
                                    if (p.getAttributeName(i).endsWith("file-as")) fileAs = p.getAttributeValue(i)
                                }
                                creators += Creator(id, p.nextText().trim(), fileAs)
                            }
                            "meta" -> {
                                // EPUB 3: <meta refines="#id" property="file-as">Cognome, Nome</meta>
                                val refines = p.getAttributeValue(null, "refines")
                                val prop = p.getAttributeValue(null, "property")
                                if (refines != null && prop == "file-as") {
                                    fileAsByRef[refines.removePrefix("#")] = p.nextText().trim()
                                }
                            }
                        }
                    } else if (ev == XmlPullParser.END_TAG && localName(p.name) == "metadata") {
                        break
                    }
                    ev = p.next()
                }

                val first = creators.firstOrNull { it.text.isNotBlank() }
                val sort = first?.fileAs?.takeIf { it.isNotBlank() }
                    ?: first?.id?.let { fileAsByRef[it] }?.takeIf { it.isNotBlank() }
                EpubMeta(title, first?.text, sort)
            } catch (e: Exception) {
                null
            }
        }

        /** "David W. Anthony" -> "Anthony, David W"; "Anthony, David W." resta com'e', senza il punto finale. */
        fun folderName(raw: String?): String? {
            var s = raw?.trim().orEmpty()
            if (s.isEmpty()) return null

            // Piu' autori: tieni il primo.
            s = s.split(';', '&').first().trim()
            if (!s.contains(',') && s.contains(" and ", ignoreCase = true)) {
                s = s.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE)).first().trim()
            }

            if (!s.contains(',')) {
                val parts = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (parts.size >= 2) {
                    s = parts.last() + ", " + parts.dropLast(1).joinToString(" ")
                }
            }

            s = s.replace(Regex("[\\\\/:*?\"<>|]"), "")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.', ' ')
                .take(60)
                .trimEnd('.', ' ')
            return s.ifEmpty { null }
        }
    }
}
