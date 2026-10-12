package com.carplay.app.diagnostics

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject

class SessionJournalTest {
    @Test fun staleFramesStatesAndLogsCannotMutateReplacementSession() {
        var now = 1_000_000_000L
        Diagnostics.testStorage(Files.createTempDirectory("carplay-journal").toFile()) { now }
        val old = Diagnostics.begin()
        Diagnostics.stage(old,Stage.BLUETOOTH,"old")
        now += 1000000
        val next = Diagnostics.begin()
        Diagnostics.stage(old,Stage.FAILED,"old failure")
        Diagnostics.event("ERROR","late old callback",old)
        Diagnostics.video(old,100)
        assertEquals(next,Diagnostics.status.value.id)
        assertEquals(Stage.IDLE,Diagnostics.status.value.stage)
        assertEquals(0,Diagnostics.status.value.frames)
        assertFalse(Diagnostics.status.value.events.any { it.message.contains("old callback") })
        assertTrue(Diagnostics.export(old).isFile)
    }
    @Test fun measuredSampleAndArchiveContainActualCountersAndRedactedEvents() {
        var now = 1_000_000_000L
        Diagnostics.testStorage(Files.createTempDirectory("carplay-metrics").toFile()) { now }
        val id = Diagnostics.begin()
        Diagnostics.stage(id,Stage.STREAMING,"sending")
        repeat(59) { now += 16_000_000; Diagnostics.video(id,1000) }
        now = 2_000_000_000L; Diagnostics.video(id,1000)
        Diagnostics.event("INFO","password=secret-value",id)
        val sample = Diagnostics.status.value.samples.single()
        assertEquals(60.0,sample.fps,0.0001); assertEquals(480.0,sample.kbps,0.0001)
        ZipFile(Diagnostics.export()).use { zip ->
            val summary = JSONObject(zip.getInputStream(zip.getEntry("session.json")).bufferedReader().readText())
            assertEquals(60,summary.getLong("frames")); assertEquals(60000,summary.getLong("bytes"))
            assertTrue(summary.toString().contains("secret-value"))
        }
    }
}
