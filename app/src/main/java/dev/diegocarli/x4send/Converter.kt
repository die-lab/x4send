package dev.diegocarli.x4send

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import org.commonmark.parser.Parser as MdParser

/** Quali file l'X4 (CrossPoint) legge cosi' come sono e quali vanno convertiti. */
object Formats {
    val NATIVE = setOf("epub", "txt", "xtc", "xtch", "bmp")
    val TO_EPUB = setOf("docx", "odt", "pdf", "rtf", "fb2", "html", "htm", "xhtml", "md", "markdown")
    val TO_BMP = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    fun isNative(name: String) = ext(name) in NATIVE
    fun isConvertible(name: String) = ext(name).let { it in TO_EPUB || it in TO_BMP }

    fun unsupported(name: String): String {
        val e = ext(name)
        return when {
            e.isEmpty() -> "tipo di file sconosciuto"
            e in setOf("mobi", "azw", "azw3", "kfx", "prc") -> "formato .$e (Kindle) non supportato: convertilo in EPUB con Calibre"
            e == "doc" -> "i vecchi .doc non sono supportati: salvali come .docx"
            else -> "formato .$e non supportato"
        }
    }
}

data class Chapter(val title: String, val body: String, val inToc: Boolean)
data class Book(val title: String, val author: String?, val chapters: List<Chapter>)
data class Converted(val file: File, val name: String)

object Converter {

    /** Oltre questa dimensione un capitolo viene spezzato: l'ESP32 dell'X4 ha poca RAM. */
    private const val MAX_CHAPTER = 80_000

    private val SAFE: Safelist = Safelist.none().addTags(
        "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "b", "strong", "i", "em", "u", "s", "sub", "sup",
        "small", "blockquote", "ul", "ol", "li", "pre", "code", "hr", "table", "thead", "tbody", "tr", "td",
        "th", "dl", "dt", "dd",
    )

    private val HEADING = Regex(
        "^(capitolo|chapter|parte|part|libro|book|prologo|prologue|epilogo|epilogue|introduzione|introduction|prefazione|preface)\\b.{0,60}$" +
            "|^[IVXLC]{1,7}\\.?$|^\\d{1,3}\\.?$",
        RegexOption.IGNORE_CASE,
    )
    private val HEAD_STYLE = Regex("(heading|titolo|t[ií]tulo|titre|berschrift|kop)\\s*([1-6])", RegexOption.IGNORE_CASE)
    private val ODT_SKIP = setOf("note", "tracked-changes", "annotation", "table-of-content", "alphabetical-index")

    fun convert(ctx: Context, name: String, open: () -> InputStream, dir: File): Converted {
        val ext = Formats.ext(name)
        val base = name.substringBeforeLast('.').trim().ifBlank { "documento" }
        if (ext in Formats.TO_BMP) {
            val out = File.createTempFile("x4img", ".bmp", dir)
            try {
                Images.toBmp(open, out)
            } catch (e: Throwable) {
                out.delete()
                throw e
            }
            return Converted(out, "$base.bmp")
        }
        val book = when (ext) {
            "docx" -> fromDocx(open, base)
            "odt" -> fromOdt(open, base)
            "pdf" -> fromPdf(ctx, open, base)
            "rtf" -> fromRtf(open, base)
            "fb2" -> fromFb2(open, base)
            "html", "htm", "xhtml" -> fromHtml(open, base)
            "md", "markdown" -> fromMarkdown(open, base)
            else -> throw IOException(Formats.unsupported(name))
        }
        val out = File.createTempFile("x4book", ".epub", dir)
        try {
            EpubWriter.write(out, book)
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return Converted(out, "$base.epub")
    }

    // ---------------------------------------------------------------- formati

    private fun fromHtml(open: () -> InputStream, base: String): Book {
        val doc = open().use { Jsoup.parse(it, null, "") }
        val title = doc.title().trim().ifBlank { base }
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.trim()?.ifBlank { null }
        return Book(title, author, chapters(doc, title))
    }

    private fun fromMarkdown(open: () -> InputStream, base: String): Book {
        val text = decodeText(open().use { it.readBytes() })
        val html = HtmlRenderer.builder().build().render(MdParser.builder().build().parse(text))
        val title = Regex("^#\\s+(.+)$", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)?.trim() ?: base
        return Book(title, null, chapters(Jsoup.parse(html), title))
    }

    private fun fromRtf(open: () -> InputStream, base: String): Book {
        val text = Rtf.toText(open().use { it.readBytes() })
        return Book(base, null, chapters(Jsoup.parse(textToHtml(text, joinLines = false)), base))
    }

    private fun fromPdf(ctx: Context, open: () -> InputStream, base: String): Book {
        PDFBoxResourceLoader.init(ctx.applicationContext)
        var text = ""
        var rawTitle = ""
        var author: String? = null
        open().use { input ->
            PDDocument.load(input).use { doc ->
                val stripper = PDFTextStripper()
                stripper.paragraphEnd = "\n\n"
                text = stripper.getText(doc)
                val info = doc.documentInformation
                rawTitle = info?.title?.trim().orEmpty()
                author = info?.author?.trim()?.ifEmpty { null }
            }
        }
        if (text.count { it.isLetter() } < 50) {
            throw IOException("il PDF non contiene testo (forse e' una scansione): non convertibile")
        }
        val junk = Regex("\\.(docx?|pdf|odt|indd)$|Microsoft", RegexOption.IGNORE_CASE)
        val title = if (rawTitle.isEmpty() || junk.containsMatchIn(rawTitle)) base else rawTitle
        return Book(title, author, chapters(Jsoup.parse(textToHtml(text, joinLines = true)), title))
    }

    private fun fromDocx(open: () -> InputStream, base: String): Book {
        val parts = readZip(open, setOf("word/document.xml", "word/styles.xml", "docProps/core.xml"))
        val docXml = parts["word/document.xml"] ?: throw IOException("DOCX non valido")
        val styles = parts["word/styles.xml"]?.let { docxStyles(it) }.orEmpty()
        val meta = parts["docProps/core.xml"]?.let { coreMeta(it) } ?: (null to null)
        val title = meta.first ?: base
        return Book(title, meta.second, chapters(Jsoup.parse(docxHtml(docXml, styles)), title))
    }

    private fun fromOdt(open: () -> InputStream, base: String): Book {
        val parts = readZip(open, setOf("content.xml", "meta.xml"))
        val content = parts["content.xml"] ?: throw IOException("ODT non valido")
        val meta = parts["meta.xml"]?.let { coreMeta(it) } ?: (null to null)
        val title = meta.first ?: base
        return Book(title, meta.second, chapters(Jsoup.parse(odtHtml(content)), title))
    }

    private fun fromFb2(open: () -> InputStream, base: String): Book {
        val doc = open().use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
        val info = doc.selectFirst("title-info")
        val title = info?.selectFirst("book-title")?.text()?.trim()?.ifEmpty { null } ?: base
        val a = info?.selectFirst("author")
        val author = a?.let { el ->
            listOf("first-name", "middle-name", "last-name")
                .mapNotNull { el.selectFirst(it)?.text()?.trim()?.ifEmpty { null } }
                .joinToString(" ")
                .ifEmpty { el.selectFirst("nickname")?.text()?.trim().orEmpty() }
        }?.ifEmpty { null }
        val sb = StringBuilder()
        for (body in doc.getElementsByTag("body")) {
            val n = body.attr("name")
            if (n == "notes" || n == "comments") continue
            fb2Block(body, 0, sb)
        }
        return Book(title, author, chapters(Jsoup.parse(sb.toString()), title))
    }

    // ---------------------------------------------------------------- HTML -> capitoli

    private fun chapters(doc: Document, bookTitle: String): List<Chapter> {
        doc.select("script, style, noscript, nav, iframe, svg, form, button, input, select, textarea, img, video, audio, canvas, object, template").remove()
        // I contenitori (div, section...) vengono sciolti o diventano paragrafi, cosi' i titoli restano al primo livello.
        val containers = doc.body().select("div, section, article, main, header, footer, aside, figure, figcaption, center, address")
        for (el in containers.reversed()) {
            if (el.children().any { it.isBlock }) el.unwrap() else el.tagName("p")
        }
        val clean = Cleaner(SAFE).clean(doc)
        clean.outputSettings()
            .syntax(Document.OutputSettings.Syntax.xml)
            .escapeMode(Entities.EscapeMode.xhtml)
            .charset(Charsets.UTF_8)
            .prettyPrint(false)

        val result = mutableListOf<Chapter>()
        var title = bookTitle
        var part = 1
        val buf = StringBuilder()
        fun emit() {
            if (buf.isNotBlank()) {
                result += Chapter(if (part == 1) title else "$title ($part)", xmlSafe(buf.toString()), part == 1)
            }
            buf.setLength(0)
        }
        for (node in clean.body().childNodes().toList()) {
            val el = node as? Element
            if (el != null && (el.normalName() == "h1" || el.normalName() == "h2")) {
                emit()
                title = el.text().trim().take(80).ifEmpty { title }
                part = 1
            } else if (buf.length > MAX_CHAPTER) {
                emit()
                part++
            }
            buf.append(node.outerHtml())
        }
        emit()
        if (result.isEmpty()) throw IOException("nessun testo trovato nel file")
        return result
    }

    /** Testo semplice -> HTML. joinLines: unisce le righe spezzate (PDF), altrimenti le tiene con <br/>. */
    private fun textToHtml(text: String, joinLines: Boolean): String {
        val sb = StringBuilder()
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').split(Regex("\\n\\s*\\n"))
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            if (joinLines && lines.size == 1 && lines[0].matches(Regex("\\d{1,4}"))) continue // numero di pagina
            val one = if (lines.size == 1) lines[0] else null
            if (one != null && one.length <= 70 && HEADING.matches(one)) {
                sb.append("<h2>").append(esc(one)).append("</h2>\n")
                continue
            }
            sb.append("<p>")
            if (joinLines) sb.append(esc(joinWrapped(lines))) else sb.append(lines.joinToString("<br/>") { esc(it) })
            sb.append("</p>\n")
        }
        return sb.toString()
    }

    private fun joinWrapped(lines: List<String>): String {
        val sb = StringBuilder()
        for (l in lines) {
            if (sb.isEmpty()) {
                sb.append(l)
            } else if (sb.endsWith("-") && l.first().isLowerCase()) {
                sb.setLength(sb.length - 1)
                sb.append(l)
            } else {
                sb.append(' ').append(l)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- DOCX / ODT

    private class Para {
        val sb = StringBuilder()
        var level = 0
    }

    private fun local(tag: String) = tag.substringAfter(':')

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            val n = p.getAttributeName(i)
            if (n == name || n.endsWith(":$name")) return p.getAttributeValue(i)
        }
        return null
    }

    private fun isOn(p: XmlPullParser): Boolean {
        val v = attr(p, "val")
        return v == null || v !in setOf("0", "false", "none", "off")
    }

    private fun readZip(open: () -> InputStream, names: Set<String>): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(open().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name in names) out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    /** Titolo e autore da docProps/core.xml (DOCX) o meta.xml (ODT). */
    private fun coreMeta(bytes: ByteArray): Pair<String?, String?> {
        var title: String? = null
        var author: String? = null
        try {
            val p = Xml.newPullParser()
            p.setInput(bytes.inputStream(), null)
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "title" -> if (title == null) title = p.nextText().trim().ifEmpty { null }
                        "creator", "initial-creator" -> if (author == null) author = p.nextText().trim().ifEmpty { null }
                    }
                }
                ev = p.next()
            }
        } catch (_: Exception) {
        }
        return title to author
    }

    private fun docxStyles(bytes: ByteArray): Map<String, String> {
        val map = HashMap<String, String>()
        try {
            val p = Xml.newPullParser()
            p.setInput(bytes.inputStream(), null)
            var id: String? = null
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    when (local(p.name)) {
                        "style" -> id = attr(p, "styleId")
                        "name" -> {
                            val styleId = id
                            val v = attr(p, "val")
                            if (styleId != null && v != null) map[styleId] = v
                        }
                    }
                } else if (ev == XmlPullParser.END_TAG && local(p.name) == "style") {
                    id = null
                }
                ev = p.next()
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun headingLevel(id: String?, styles: Map<String, String>): Int {
        if (id == null) return 0
        for (s in listOfNotNull(styles[id], id)) {
            HEAD_STYLE.find(s)?.let { return it.groupValues[2].toInt() }
            if (s.equals("title", true) || s.equals("titolo", true)) return 1
        }
        return 0
    }

    private fun appendRun(sb: StringBuilder, text: String, bold: Boolean, italic: Boolean) {
        val e = esc(text)
        if (e.isEmpty()) return
        if (bold) sb.append("<b>")
        if (italic) sb.append("<i>")
        sb.append(e)
        if (italic) sb.append("</i>")
        if (bold) sb.append("</b>")
    }

    private fun emitPara(out: StringBuilder, html: String, level: Int) {
        if (html.isBlank()) return
        if (level in 1..6) out.append("<h$level>").append(html).append("</h$level>\n")
        else out.append("<p>").append(html).append("</p>\n")
    }

    private fun docxHtml(xml: ByteArray, styles: Map<String, String>): String {
        val out = StringBuilder()
        val stack = ArrayDeque<Para>()
        var inRun = false
        var inRPr = false
        var bold = false
        var italic = false
        val p = Xml.newPullParser()
        p.setInput(xml.inputStream(), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (local(p.name)) {
                    "p" -> stack.addLast(Para())
                    "pStyle" -> stack.lastOrNull()?.let { it.level = headingLevel(attr(p, "val"), styles) }
                    "outlineLvl" -> stack.lastOrNull()?.let { para ->
                        val v = attr(p, "val")?.toIntOrNull()
                        if (para.level == 0 && v != null && v in 0..5) para.level = v + 1
                    }
                    "r" -> {
                        inRun = true
                        bold = false
                        italic = false
                    }
                    "rPr" -> inRPr = true
                    "b" -> if (inRun && inRPr) bold = isOn(p)
                    "i" -> if (inRun && inRPr) italic = isOn(p)
                    "t" -> {
                        val txt = p.nextText()
                        stack.lastOrNull()?.let { appendRun(it.sb, txt, bold, italic) }
                    }
                    "tab" -> if (inRun) stack.lastOrNull()?.sb?.append(' ')
                    "br", "cr" -> if (inRun) stack.lastOrNull()?.sb?.append("<br/>")
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (local(p.name)) {
                    "rPr" -> inRPr = false
                    "r" -> inRun = false
                    "p" -> stack.removeLastOrNull()?.let { emitPara(out, it.sb.toString(), it.level) }
                }
            }
            ev = p.next()
        }
        return out.toString()
    }

    private fun odtHtml(xml: ByteArray): String {
        val out = StringBuilder()
        val stack = ArrayDeque<Para>()
        var skipDepth = 0
        val p = Xml.newPullParser()
        p.setInput(xml.inputStream(), null)
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            val name = if (ev == XmlPullParser.START_TAG || ev == XmlPullParser.END_TAG) local(p.name) else ""
            if (skipDepth > 0) {
                // Dentro note, commenti o indici: si ignora tutto fino alla chiusura.
                if (ev == XmlPullParser.END_TAG && p.depth == skipDepth && name in ODT_SKIP) skipDepth = 0
            } else if (ev == XmlPullParser.START_TAG) {
                when (name) {
                    in ODT_SKIP -> skipDepth = p.depth
                    "p" -> stack.addLast(Para())
                    "h" -> stack.addLast(Para().also { it.level = (attr(p, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6) })
                    "s" -> stack.lastOrNull()?.sb?.append(" ".repeat((attr(p, "c")?.toIntOrNull() ?: 1).coerceIn(1, 20)))
                    "tab" -> stack.lastOrNull()?.sb?.append(' ')
                    "line-break" -> stack.lastOrNull()?.sb?.append("<br/>")
                }
            } else if (ev == XmlPullParser.TEXT) {
                stack.lastOrNull()?.sb?.append(esc(p.text.orEmpty()))
            } else if (ev == XmlPullParser.END_TAG && (name == "p" || name == "h")) {
                stack.removeLastOrNull()?.let { emitPara(out, it.sb.toString(), it.level) }
            }
            ev = p.next()
        }
        return out.toString()
    }

    // ---------------------------------------------------------------- FB2

    private fun fb2Block(el: Element, depth: Int, sb: StringBuilder) {
        for (node in el.childNodes()) {
            if (node !is Element) continue
            when (node.normalName()) {
                "section" -> fb2Block(node, depth + 1, sb)
                "title" -> {
                    val lvl = (depth + 1).coerceAtMost(4)
                    val t = node.children().joinToString(" ") { it.text().trim() }.ifBlank { node.text() }.trim()
                    if (t.isNotEmpty()) sb.append("<h$lvl>").append(esc(t)).append("</h$lvl>\n")
                }
                "p", "v", "text-author" -> sb.append("<p>").append(fb2Inline(node)).append("</p>\n")
                "subtitle" -> sb.append("<p><b>").append(fb2Inline(node)).append("</b></p>\n")
                "empty-line" -> sb.append("<p>&#160;</p>\n")
                "epigraph", "cite", "annotation" -> {
                    sb.append("<blockquote>")
                    fb2Block(node, depth, sb)
                    sb.append("</blockquote>\n")
                }
                "image", "binary" -> {}
                else -> fb2Block(node, depth, sb)
            }
        }
    }

    private fun fb2Inline(el: Element): String {
        val sb = StringBuilder()
        for (n in el.childNodes()) {
            if (n is TextNode) {
                sb.append(esc(n.wholeText))
            } else if (n is Element) {
                val tag = when (n.normalName()) {
                    "emphasis" -> "i"
                    "strong" -> "b"
                    "strikethrough" -> "s"
                    "sub" -> "sub"
                    "sup" -> "sup"
                    "code" -> "code"
                    else -> null
                }
                val inner = fb2Inline(n)
                if (tag != null) sb.append("<$tag>").append(inner).append("</$tag>") else sb.append(inner)
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- utilita'

    private fun decodeText(b: ByteArray): String {
        if (b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) {
            return String(b, 3, b.size - 3, Charsets.UTF_8)
        }
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16LE)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, 2, b.size - 2, Charsets.UTF_16BE)
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b))
                .toString()
        } catch (e: CharacterCodingException) {
            String(b, Charset.forName("windows-1252"))
        }
    }
}

private val BAD_XML = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")

internal fun xmlSafe(s: String): String = BAD_XML.replace(s, "")

internal fun esc(s: String): String =
    xmlSafe(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/** Scrive un EPUB 3 minimale (con toc.ncx per i lettori EPUB 2). */
object EpubWriter {

    private const val CSS = """body { margin: 0; }
p { margin: 0 0 0.6em 0; }
h1, h2, h3, h4 { margin: 1em 0 0.6em 0; }
blockquote { margin: 0.6em 1.2em; }
"""

    fun write(out: File, book: Book) {
        val uuid = UUID.randomUUID().toString()
        val lang = Locale.getDefault().language.ifEmpty { "it" }
        val title = esc(book.title)
        val files = book.chapters.indices.map { String.format(Locale.US, "c%03d.xhtml", it + 1) }
        val modified = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date())

        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            // "mimetype" deve essere la prima voce, non compressa.
            val mt = "application/epub+zip".toByteArray()
            val entry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mt.size.toLong()
                compressedSize = mt.size.toLong()
                crc = CRC32().apply { update(mt) }.value
            }
            zip.putNextEntry(entry)
            zip.write(mt)
            zip.closeEntry()

            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            put(
                "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
""",
            )

            val opf = StringBuilder()
            opf.append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="$lang">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">urn:uuid:$uuid</dc:identifier>
<dc:title>$title</dc:title>
<dc:language>$lang</dc:language>
<meta property="dcterms:modified">$modified</meta>
""")
            book.author?.let { a ->
                opf.append("<dc:creator id=\"creator\">").append(esc(a)).append("</dc:creator>\n")
                EpubMeta.folderName(a)?.let { fa ->
                    opf.append("<meta refines=\"#creator\" property=\"file-as\">").append(esc(fa)).append("</meta>\n")
                }
            }
            opf.append("</metadata>\n<manifest>\n")
            opf.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            opf.append("<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n")
            opf.append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            files.forEachIndexed { i, f ->
                opf.append("<item id=\"c${i + 1}\" href=\"$f\" media-type=\"application/xhtml+xml\"/>\n")
            }
            opf.append("</manifest>\n<spine toc=\"ncx\">\n")
            files.indices.forEach { opf.append("<itemref idref=\"c${it + 1}\"/>\n") }
            opf.append("</spine>\n</package>\n")
            put("OEBPS/content.opf", opf.toString())

            val toc = book.chapters.indices.filter { book.chapters[it].inToc }.ifEmpty { listOf(0) }

            val nav = StringBuilder()
            nav.append("""<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="$lang" xml:lang="$lang">
<head><title>$title</title></head>
<body><nav epub:type="toc" id="toc"><h1>$title</h1><ol>
""")
            toc.forEach { nav.append("<li><a href=\"${files[it]}\">").append(esc(book.chapters[it].title)).append("</a></li>\n") }
            nav.append("</ol></nav></body></html>\n")
            put("OEBPS/nav.xhtml", nav.toString())

            val ncx = StringBuilder()
            ncx.append("""<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
<head><meta name="dtb:uid" content="urn:uuid:$uuid"/><meta name="dtb:depth" content="1"/><meta name="dtb:totalPageCount" content="0"/><meta name="dtb:maxPageNumber" content="0"/></head>
<docTitle><text>$title</text></docTitle>
<navMap>
""")
            toc.forEachIndexed { order, i ->
                ncx.append("<navPoint id=\"p${order + 1}\" playOrder=\"${order + 1}\"><navLabel><text>")
                    .append(esc(book.chapters[i].title))
                    .append("</text></navLabel><content src=\"${files[i]}\"/></navPoint>\n")
            }
            ncx.append("</navMap>\n</ncx>\n")
            put("OEBPS/toc.ncx", ncx.toString())

            put("OEBPS/style.css", CSS)

            book.chapters.forEachIndexed { i, ch ->
                put(
                    "OEBPS/${files[i]}",
                    """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" lang="$lang" xml:lang="$lang">
<head><title>${esc(ch.title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
${ch.body}
</body>
</html>
""",
                )
            }
        }
    }
}

/** Immagini -> BMP 24 bit in scala di grigi, ridotte per stare nello schermo 480x800 dell'X4. */
object Images {
    private const val W = 480
    private const val H = 800

    fun toBmp(open: () -> InputStream, out: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("immagine non leggibile")
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= W && bounds.outHeight / (sample * 2) >= H) sample *= 2
        val src = open().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IOException("immagine non leggibile")
        val scale = min(W.toFloat() / src.width, H.toFloat() / src.height).coerceAtMost(1f)
        val w = max(1, (src.width * scale).roundToInt())
        val h = max(1, (src.height * scale).roundToInt())
        val bmp: Bitmap = if (w != src.width || h != src.height) Bitmap.createScaledBitmap(src, w, h, true) else src

        val row = (w * 3 + 3) / 4 * 4
        val dataSize = row * h
        val header = ByteBuffer.allocate(54).order(ByteOrder.LITTLE_ENDIAN)
        header.put('B'.code.toByte()).put('M'.code.toByte())
        header.putInt(54 + dataSize).putInt(0).putInt(54)
        header.putInt(40).putInt(w).putInt(h).putShort(1.toShort()).putShort(24.toShort())
        header.putInt(0).putInt(dataSize).putInt(2835).putInt(2835).putInt(0).putInt(0)

        out.outputStream().buffered().use { os ->
            os.write(header.array())
            val pixels = IntArray(w)
            val line = ByteArray(row)
            for (y in h - 1 downTo 0) {
                bmp.getPixels(pixels, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    val c = pixels[x]
                    val a = (c ushr 24) and 0xff
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    var gray = (r * 299 + g * 587 + b * 114) / 1000
                    gray = (gray * a + 255 * (255 - a)) / 255 // trasparenza su fondo bianco
                    val v = gray.toByte()
                    line[x * 3] = v
                    line[x * 3 + 1] = v
                    line[x * 3 + 2] = v
                }
                os.write(line)
            }
        }
    }
}

/** Estrattore RTF minimale: testo, paragrafi, accenti e caratteri Unicode. */
object Rtf {

    private data class State(var skip: Boolean = false, var uc: Int = 1)

    private val SKIP_DEST = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "pict", "header", "footer", "headerl", "headerr",
        "headerf", "footerl", "footerr", "footerf", "object", "themedata", "colorschememapping", "datastore",
        "latentstyles", "listtable", "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "mmathPr",
        "fldinst", "filetbl", "revtbl", "footnote",
    )

    private fun isAsciiLetter(c: Int) = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code

    fun toText(bytes: ByteArray): String {
        val sb = StringBuilder()
        var cs: Charset = Charset.forName("windows-1252")
        val stack = ArrayDeque<State>()
        var st = State()
        var pendingSkip = 0
        val hex = ByteArrayOutputStream()
        fun flushHex() {
            if (hex.size() > 0) {
                if (!st.skip) sb.append(String(hex.toByteArray(), cs))
                hex.reset()
            }
        }
        fun emit(s: String) {
            flushHex()
            if (!st.skip) sb.append(s)
        }

        var i = 0
        val n = bytes.size
        while (i < n) {
            val c = bytes[i].toInt() and 0xff
            when (c) {
                '{'.code -> {
                    flushHex()
                    stack.addLast(st.copy())
                    i++
                }
                '}'.code -> {
                    flushHex()
                    st = stack.removeLastOrNull() ?: st
                    i++
                }
                '\\'.code -> {
                    i++
                    if (i >= n) break
                    val d = bytes[i].toInt() and 0xff
                    when {
                        d == '\''.code -> {
                            val h = if (i + 2 < n) String(bytes, i + 1, 2, Charsets.US_ASCII) else ""
                            i += 3
                            val v = h.toIntOrNull(16)
                            if (pendingSkip > 0) pendingSkip-- else if (v != null) hex.write(v)
                        }
                        isAsciiLetter(d) -> {
                            val start = i
                            while (i < n && isAsciiLetter(bytes[i].toInt() and 0xff)) i++
                            val word = String(bytes, start, i - start, Charsets.US_ASCII)
                            val pStart = i
                            if (i < n && bytes[i] == '-'.code.toByte()) i++
                            while (i < n && (bytes[i].toInt() and 0xff).toChar().isDigit()) i++
                            val param = if (i > pStart) String(bytes, pStart, i - pStart, Charsets.US_ASCII).toIntOrNull() else null
                            if (i < n && bytes[i] == ' '.code.toByte()) i++
                            when (word) {
                                in SKIP_DEST -> {
                                    flushHex()
                                    st.skip = true
                                }
                                "par", "sect", "page", "row" -> emit("\n\n")
                                "line" -> emit("\n")
                                "tab", "cell" -> emit(" ")
                                "emdash" -> emit("—")
                                "endash" -> emit("–")
                                "lquote" -> emit("‘")
                                "rquote" -> emit("’")
                                "ldblquote" -> emit("“")
                                "rdblquote" -> emit("”")
                                "bullet" -> emit("•")
                                "uc" -> st.uc = param ?: 1
                                "u" -> {
                                    var v = param ?: 0
                                    if (v < 0) v += 65536
                                    emit(v.toChar().toString())
                                    pendingSkip = st.uc
                                }
                                "ansicpg" -> param?.let {
                                    try {
                                        cs = Charset.forName("windows-$it")
                                    } catch (_: Exception) {
                                    }
                                }
                            }
                        }
                        d == '*'.code -> {
                            flushHex()
                            st.skip = true
                            i++
                        }
                        d == '\\'.code || d == '{'.code || d == '}'.code -> {
                            emit(d.toChar().toString())
                            i++
                        }
                        d == '~'.code -> {
                            emit(" ")
                            i++
                        }
                        d == '_'.code -> {
                            emit("-")
                            i++
                        }
                        d == '\n'.code || d == '\r'.code -> {
                            emit("\n\n")
                            i++
                        }
                        else -> i++
                    }
                }
                '\r'.code, '\n'.code -> i++
                else -> {
                    if (pendingSkip > 0) {
                        pendingSkip--
                    } else {
                        emit(c.toChar().toString())
                    }
                    i++
                }
            }
        }
        flushHex()
        return sb.toString()
    }
}
