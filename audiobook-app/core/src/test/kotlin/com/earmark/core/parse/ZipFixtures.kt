package com.earmark.core.parse

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipFixtures {
    fun zip(vararg entries: Pair<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    const val CONTAINER = """<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>$title</title></head>
<body>$body</body></html>"""

    /** EPUB3 with a nav document, a file name with a space, a non-linear footnote file and a cover image. */
    fun epub3(): ByteArray = zip(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to CONTAINER,
        "OEBPS/content.opf" to """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>The Lighthouse Keeper</dc:title><dc:creator>Asha Verma</dc:creator>
  </metadata>
  <manifest>
    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
    <item id="c1" href="text/chapter%201.xhtml" media-type="application/xhtml+xml"/>
    <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="notes" href="text/notes.xhtml" media-type="application/xhtml+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
  </manifest>
  <spine><itemref idref="c1"/><itemref idref="notes" linear="no"/><itemref idref="c2"/></spine>
</package>""",
        "OEBPS/nav.xhtml" to xhtml("Nav", """<nav epub:type="toc"><ol>
<li><a href="text/chapter%201.xhtml">One: Arrival</a></li><li><a href="text/ch2.xhtml#start">Two: The Storm</a></li></ol></nav>"""),
        "OEBPS/text/chapter 1.xhtml" to xhtml("c1", """<h1>Arrival</h1>
<p>Mira reached the harbour at dawn.<sup><a epub:type="noteref" href="notes.xhtml#n1">1</a></sup> The fog hid the boats.</p>
<p>An old sailor waved at her &amp; smiled.<br/>His name was Tobin.</p>
<script>alert('never read me')</script>
<aside epub:type="footnote"><p>Footnote text that should be skipped.</p></aside>"""),
        "OEBPS/text/notes.xhtml" to xhtml("Notes", "<p>1. Dawn is early.</p>"),
        "OEBPS/text/ch2.xhtml" to xhtml("c2", """<section><h2 id="start">The Storm</h2><div><p>By noon the sky turned <em>black</em>. Thunder rolled.</p></div>
<ul><li>First item</li><li>Second item</li></ul></section>"""),
    )

    /** EPUB2 with an NCX table of contents and "../" relative paths. */
    fun epub2(): ByteArray = zip(
        "META-INF/container.xml" to CONTAINER.replace("OEBPS/content.opf", "OPS/pkg/book.opf"),
        "OPS/pkg/book.opf" to """<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Old Format</dc:title></metadata>
  <manifest>
    <item id="ncx" href="../toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="a" href="../xhtml/a.html" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx"><itemref idref="a"/></spine>
</package>""",
        "OPS/toc.ncx" to """<?xml version="1.0"?><ncx><navMap><navPoint id="p1"><navLabel><text>Prologue</text></navLabel><content src="xhtml/a.html"/></navPoint></navMap></ncx>""",
        "OPS/xhtml/a.html" to xhtml("a", "<p>It was a dark night. Nobody slept.</p>"),
    )

    fun docx(): ByteArray = zip(
        "[Content_Types].xml" to "<Types/>",
        "docProps/core.xml" to """<cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Case Notes</dc:title><dc:creator>R. Singh</dc:creator></cp:coreProperties>""",
        "word/document.xml" to """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr><w:r><w:t>Case Notes</w:t></w:r></w:p>
<w:p><w:r><w:t xml:space="preserve">The accused was </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>arrested</w:t></w:r><w:r><w:t xml:space="preserve"> on Monday.</w:t></w:r><w:r><w:footnoteReference w:id="1"/></w:r></w:p>
<w:p/>
<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Evidence</w:t></w:r></w:p>
<w:p><w:r><w:t>Item</w:t></w:r><w:r><w:tab/></w:r><w:r><w:t>one was a knife.</w:t></w:r></w:p>
</w:body></w:document>""",
    )
}
