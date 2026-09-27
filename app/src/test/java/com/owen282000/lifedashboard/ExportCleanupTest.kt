package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportCleanupTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun export(name: String, writtenAt: Long): File =
        folder.newFile(name).apply { setLastModified(writtenAt) }

    @Test
    fun `exports written before the cutoff are deleted and newer ones stay`() {
        export("old.json", writtenAt = 1_000_000L)
        export("new.csv", writtenAt = 3_000_000L)

        ExportManager.deleteExportsOlderThan(folder.root, cutoffMillis = 2_000_000L)

        assertEquals(listOf("new.csv"), folder.root.list()!!.toList())
    }

    @Test
    fun `a new export clears every previous one`() {
        export("logs.json", writtenAt = System.currentTimeMillis())

        ExportManager.deleteExportsOlderThan(folder.root, cutoffMillis = Long.MAX_VALUE)

        assertEquals(emptyList<String>(), folder.root.list()!!.toList())
    }

    @Test
    fun `a missing exports directory is not an error`() {
        ExportManager.deleteExportsOlderThan(File(folder.root, "exports"), cutoffMillis = Long.MAX_VALUE)
    }
}
