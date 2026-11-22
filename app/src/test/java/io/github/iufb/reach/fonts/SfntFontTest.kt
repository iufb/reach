package io.github.iufb.reach.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SfntFontTest {

    @Test fun cbdtIsEmoji() = assertKind(FontKind.Emoji, font("CBDT", "CBLC", "glyf"))

    @Test fun colrIsEmoji() = assertKind(FontKind.Emoji, font("COLR", "CPAL", "glyf"))

    @Test fun glyfIsText() = assertKind(FontKind.Text, font("glyf", "loca", "head"))

    @Test fun cffIsText() = assertKind(FontKind.Text, font("CFF ", "head", version = OTTO))

    @Test fun colorWinsOverSbix() = assertKind(FontKind.Emoji, font("sbix", "CBDT", "CBLC"))

    @Test fun sbixOnlyIsRejected() = assertProblem(FontProblem.Sbix, font("sbix", "glyf"))

    @Test fun cbdtWithoutCblcIsNotEmoji() = assertProblem(FontProblem.NoGlyphs, font("CBDT"))

    @Test fun collectionIsRejected() = assertProblem(FontProblem.Collection, font("glyf", version = TTCF))

    @Test fun emptyFile() = assertProblem(FontProblem.Empty, ByteArray(0))

    @Test fun truncatedHeader() = assertProblem(FontProblem.Truncated, font("glyf").copyOf(8))

    @Test fun truncatedDirectory() = assertProblem(FontProblem.Truncated, font("glyf", "head").copyOf(12 + 16 + 4))

    @Test fun truncatedTableData() {
        val bytes = font("glyf", "head")
        assertProblem(FontProblem.Truncated, bytes.copyOf(bytes.size - 1))
    }

    @Test fun notSfnt() = assertProblem(FontProblem.NotFont, "PK\u0003\u0004 not a font at all".toByteArray())

    @Test fun noTables() = assertProblem(FontProblem.NoGlyphs, font())

    @Test fun typographicFamilyPreferred() {
        val name = nameTable(1 to "SF Pro Display", 16 to "SF Pro")
        assertEquals("SF Pro", SfntFont.inspect(font("glyf", tables = mapOf("name" to name))).getOrThrow().name)
    }

    @Test fun familyFallback() {
        val name = nameTable(1 to "Apple Color Emoji", 2 to "Regular")
        val info = SfntFont.inspect(font("CBDT", "CBLC", tables = mapOf("name" to name))).getOrThrow()
        assertEquals(FontInfo(FontKind.Emoji, "Apple Color Emoji"), info)
    }

    @Test fun noNameTable() = assertNull(SfntFont.inspect(font("glyf")).getOrThrow().name)

    @Test fun macSystemFontIsSfPro() {
        val name = nameTable(1 to "System Font", 6 to ".SFNS-Regular")
        val info = SfntFont.inspect(font("glyf", tables = mapOf("name" to name))).getOrThrow()
        assertEquals("System Font", info.name)
        assertEquals(".SFNS-Regular", info.postScriptName)
        assertTrue(info.isSfPro)
    }

    @Test fun appleDownloadIsSfPro() =
        assertTrue(FontInfo(FontKind.Text, "SF Pro", "SFPro-Regular").isSfPro)

    @Test fun otherFontIsNotSfPro() {
        assertFalse(FontInfo(FontKind.Text, "Inter", "Inter-Regular").isSfPro)
        assertFalse(FontInfo(FontKind.Text, "Some", null).isSfPro)
    }

    private fun assertKind(kind: FontKind, bytes: ByteArray) {
        assertEquals(kind, SfntFont.inspect(bytes).getOrThrow().kind)
    }

    private fun assertProblem(problem: FontProblem, bytes: ByteArray) {
        val error = SfntFont.inspect(bytes).exceptionOrNull()
        assertTrue("expected $problem, got $error", error is FontImportException)
        assertEquals(problem, (error as FontImportException).problem)
    }

    /** Минимальный sfnt: заголовок, каталог таблиц и по 4 байта данных на таблицу (или [tables]). */
    private fun font(vararg tags: String, version: Int = TRUETYPE, tables: Map<String, ByteArray> = emptyMap()): ByteArray {
        val all = tags.associateWith { byteArrayOf(0, 0, 0, 0) } + tables
        val out = ByteArrayOutputStream()
        out.u32(version)
        out.u16(all.size)
        out.u16(0)
        out.u16(0)
        out.u16(0)
        var offset = 12 + all.size * 16
        for ((tag, data) in all) {
            out.write(tag.toByteArray(Charsets.ISO_8859_1))
            out.u32(0)
            out.u32(offset)
            out.u32(data.size)
            offset += data.size
        }
        all.values.forEach { out.write(it) }
        return out.toByteArray()
    }

    /** Таблица `name` формата 0: записи Windows / UTF-16BE / en-US. */
    private fun nameTable(vararg names: Pair<Int, String>): ByteArray {
        val strings = names.map { it.second.toByteArray(Charsets.UTF_16BE) }
        val out = ByteArrayOutputStream()
        out.u16(0)
        out.u16(names.size)
        out.u16(6 + names.size * 12)
        var offset = 0
        names.forEachIndexed { i, (id, _) ->
            out.u16(3)
            out.u16(1)
            out.u16(0x409)
            out.u16(id)
            out.u16(strings[i].size)
            out.u16(offset)
            offset += strings[i].size
        }
        strings.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v shr 8 and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(v: Int) {
        u16(v ushr 16)
        u16(v and 0xFFFF)
    }

    private companion object {
        const val TRUETYPE = 0x00010000
        const val OTTO = 0x4F54544F
        const val TTCF = 0x74746366
    }
}
