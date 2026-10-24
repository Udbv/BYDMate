package com.bydmate.app.helper.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** «Надіслати останній запис» picks the newest recording by its timestamped name, nothing else. */
class LatestFidRecordingTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `the newest recording wins over older ones and unrelated files`() {
        tmp.newFile("bydmate_fidrec_20261024_101500.txt")
        tmp.newFile("bydmate_fidrec_20261024_163617.txt")
        tmp.newFile("bydmate_logs_20261024_170000.txt")
        tmp.newFile("bydmate_fidrec_20261024_170000.zip")

        assertEquals("bydmate_fidrec_20261024_163617.txt", latestFidRecording(tmp.root)?.name)
    }

    @Test fun `no recording or no folder gives null`() {
        tmp.newFile("bydmate_logs_20261024_170000.txt")
        assertNull(latestFidRecording(tmp.root))
        assertNull(latestFidRecording(File(tmp.root, "missing")))
    }

    @Test fun `the name it looks for is the one a run writes`() {
        assertEquals(true, FidRecLog.nameFor(0L).startsWith("bydmate_fidrec_"))
    }
}
