package io.github.meepdong.talaria.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenedFilesTest {
    @Test
    fun namesStayInsideTheFolder() {
        assertEquals("report.pdf", OpenedFiles.safeName("../../report.pdf"))
        assertEquals("evil.txt", OpenedFiles.safeName("..\\..\\evil.txt"))
        assertEquals("a_b_.txt", OpenedFiles.safeName("a:b?.txt"))
        assertEquals("file", OpenedFiles.safeName(".."))
    }

    @Test
    fun programFilesAreRefused() {
        assertTrue(OpenedFiles.runs("setup.EXE"))
        assertTrue(OpenedFiles.runs("tool.ps1"))
        assertFalse(OpenedFiles.runs("notes.pdf"))
        assertFalse(OpenedFiles.runs("README"))
        val opened = OpenedFiles(Files.createTempDirectory("opened").toFile(), windows = true)
        assertFailsWith<IllegalArgumentException> { opened.target("run.bat") }  // refused before anything is downloaded
    }
}
