package com.bydmate.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** The fid recording goes to the bot zipped: one entry, named and filled like the source. */
class ZipForUploadTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the zip holds the source under its own name`() {
        val source = tmp.newFile("bydmate_fidrec_20261024_102300.txt").apply { writeText("dev=1000 fid=1 int=1\n".repeat(500)) }
        val out = tmp.newFolder("cache")

        val zip = zipForUpload(source, out)

        assertEquals(File(out, "bydmate_fidrec_20261024_102300.zip"), zip)
        ZipFile(zip).use { z ->
            val entries = z.entries().toList()
            assertEquals(listOf(source.name), entries.map { it.name })
            assertEquals(source.readText(), z.getInputStream(entries.single()).bufferedReader().readText())
        }
    }

    @Test fun `an unreadable source throws and leaves no zip behind`() {
        val out = tmp.newFolder("cache")

        assertThrows(IOException::class.java) { zipForUpload(File(tmp.root, "gone.txt"), out) }
        assertFalse(File(out, "gone.zip").exists())
        assertNull(out.listFiles()?.firstOrNull())
    }
}
