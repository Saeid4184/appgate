package ir.factory.entryexit.util

import java.io.File
import java.io.OutputStreamWriter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a minimal but fully valid .xlsx (Office Open XML spreadsheet) file, without pulling in
 * a heavy dependency like Apache POI (which has known compatibility problems on Android).
 *
 * Cells are written as inline strings (`t="inlineStr"`), which keeps the implementation simple
 * (no shared-strings table bookkeeping) while still opening correctly in Excel, Google Sheets,
 * and LibreOffice. Every sheet is marked right-to-left so Persian columns read naturally.
 *
 * Supports multiple sheets in one workbook (e.g. a raw "detail" sheet for pivot-table analysis
 * plus a human-readable "summary" sheet), so the exported file works both for accounting
 * (readable rows) and analysis (structured, one-row-per-event data).
 */
object XlsxWriter {

    data class Sheet(val name: String, val headers: List<String>, val rows: List<List<String>>)

    /** Single-sheet convenience overload (kept for simple exports). */
    fun write(destination: File, sheetName: String, headers: List<String>, rows: List<List<String>>) {
        write(destination, listOf(Sheet(sheetName, headers, rows)))
    }

    /** Multi-sheet export: each [Sheet] becomes its own tab in the workbook, in order. */
    fun write(destination: File, sheets: List<Sheet>) {
        require(sheets.isNotEmpty()) { "At least one sheet is required" }
        destination.parentFile?.mkdirs()
        ZipOutputStream(destination.outputStream()).use { zip ->
            writeEntry(zip, "[Content_Types].xml", contentTypesXml(sheets.size))
            writeEntry(zip, "_rels/.rels", rootRelsXml())
            writeEntry(zip, "xl/workbook.xml", workbookXml(sheets))
            writeEntry(zip, "xl/_rels/workbook.xml.rels", workbookRelsXml(sheets.size))
            writeEntry(zip, "xl/styles.xml", stylesXml())
            sheets.forEachIndexed { index, sheet ->
                writeEntry(zip, "xl/worksheets/sheet${index + 1}.xml", sheetXml(sheet.headers, sheet.rows))
            }
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(zip, Charsets.UTF_8)
        writer.write(content)
        writer.flush()
        zip.closeEntry()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun contentTypesXml(sheetCount: Int): String {
        val overrides = (1..sheetCount).joinToString("\n  ") { i ->
            """<Override PartName="/xl/worksheets/sheet$i.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  $overrides
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
    }

    private fun rootRelsXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(sheets: List<Sheet>): String {
        val entries = sheets.mapIndexed { index, sheet ->
            val sheetNum = index + 1
            """<sheet name="${escapeXml(sheet.name)}" sheetId="$sheetNum" r:id="rId$sheetNum"/>"""
        }.joinToString("\n    ")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    $entries
  </sheets>
</workbook>"""
    }

    private fun workbookRelsXml(sheetCount: Int): String {
        val sheetRels = (1..sheetCount).joinToString("\n  ") { i ->
            """<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$i.xml"/>"""
        }
        val stylesRelId = sheetCount + 1
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  $sheetRels
  <Relationship Id="rId$stylesRelId" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun stylesXml() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><sz val="11"/><name val="Calibri"/></font>
    <font><sz val="11"/><name val="Calibri"/><b/></font>
  </fonts>
  <fills count="1"><fill><patternFill patternType="none"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
  </cellXfs>
</styleSheet>"""

    /** Converts a 0-based column index to a spreadsheet column letter: 0->A, 1->B, 26->AA, ... */
    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, ('A' + (i % 26)))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private fun cellXml(colIndex: Int, rowIndex: Int, value: String, headerStyle: Boolean): String {
        val ref = "${columnLetter(colIndex)}$rowIndex"
        val style = if (headerStyle) " s=\"1\"" else ""
        return "<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${escapeXml(value)}</t></is></c>"
    }

    private fun sheetXml(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        sb.append("\n<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")

        // Right-to-left sheet view so Persian columns read in natural order.
        sb.append("<sheetViews><sheetView rightToLeft=\"1\" workbookViewId=\"0\"/></sheetViews>")

        // Reasonable default column widths.
        sb.append("<cols>")
        for (i in headers.indices) {
            sb.append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"22\" customWidth=\"1\"/>")
        }
        sb.append("</cols>")

        sb.append("<sheetData>")

        // Header row (bold style).
        sb.append("<row r=\"1\">")
        for ((colIndex, header) in headers.withIndex()) {
            sb.append(cellXml(colIndex, 1, header, headerStyle = true))
        }
        sb.append("</row>")

        // One row per log entry — never compressed into a single cell.
        for ((rowOffset, row) in rows.withIndex()) {
            val rowIndex = rowOffset + 2
            sb.append("<row r=\"$rowIndex\">")
            for ((colIndex, value) in row.withIndex()) {
                sb.append(cellXml(colIndex, rowIndex, value, headerStyle = false))
            }
            sb.append("</row>")
        }

        sb.append("</sheetData>")
        sb.append("</worksheet>")
        return sb.toString()
    }
}
