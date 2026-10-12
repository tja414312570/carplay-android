package com.carplay.app.diagnostics

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.zip.ZipFile

class ComponentPreviewJournalTest {
    @Test fun localEvidenceIsExportedSeparatelyFromNetworkSendCounters() {
        storage()
        val id = Diagnostics.begin("VERIFY_COMPONENT")
        Diagnostics.preview(id, ComponentPreviewMetrics(1280, 720, 45, 29.7, 12, 815))
        Diagnostics.stage(id, Stage.STREAMING, "组件验证成功：本地预览")
        val json = summary(id)
        assertEquals("VERIFY_COMPONENT", json.getString("mode"))
        assertEquals(0, json.getLong("frames"))
        assertEquals(0, json.getLong("bytes"))
        assertEquals(0, json.getLong("audioPackets"))
        assertEquals(0, json.getJSONArray("samples").length())
        val local = json.getJSONObject("componentPreview")
        assertEquals(45, local.getLong("displayedFrames"))
        assertEquals(29.7, local.getDouble("displayFps"), 0.001)
        assertEquals(12, local.getLong("audioCallbacks"))
        assertEquals(815, local.getLong("firstFrameMs"))
        assertFalse(json.toString().contains("创建无线 CarPlay 会话"))
    }
    @Test fun waitingHasNoFabricatedFirstFrameAndNativeFailureIsRetained() {
        storage()
        val id = Diagnostics.begin("VERIFY_COMPONENT")
        Diagnostics.preview(id, ComponentPreviewMetrics(1280, 720, 0, 0.0, 0, null))
        Diagnostics.stage(id, Stage.FAILED, "vivo 宿主拒绝组件版本／签名校验")
        val json = summary(id)
        assertEquals("FAILED", json.getString("stage"))
        assertTrue(json.getJSONObject("componentPreview").isNull("firstFrameMs"))
        assertEquals(0, json.getJSONObject("componentPreview").getLong("displayedFrames"))
    }
    @Test fun latePreviewMetricsCannotAlterAReplacementCarPlaySession() {
        storage()
        val old = Diagnostics.begin("VERIFY_COMPONENT")
        val current = Diagnostics.begin()
        Diagnostics.preview(old, ComponentPreviewMetrics(1280, 720, 99, 60.0, 3, 100))
        assertEquals(current, Diagnostics.status.value.id)
        assertNull(Diagnostics.status.value.componentPreview)
        assertEquals("CARPLAY", summary(current).getString("mode"))
        assertFalse(summary(current).has("componentPreview"))
    }
    @Test fun invalidMeasurementsAreRejected() {
        storage()
        val id = Diagnostics.begin("VERIFY_COMPONENT")
        listOf(
            ComponentPreviewMetrics(0, 720, 0, 0.0, 0, null),
            ComponentPreviewMetrics(1280, 720, -1, 0.0, 0, null),
            ComponentPreviewMetrics(1280, 720, 1, Double.NaN, 0, 100),
            ComponentPreviewMetrics(1280, 720, 1, -1.0, 0, 100),
        ).forEach { metrics -> assertThrows(IllegalArgumentException::class.java) { Diagnostics.preview(id, metrics) } }
        assertNull(Diagnostics.status.value.componentPreview)
    }
    private fun storage() = Diagnostics.testStorage(Files.createTempDirectory("component-preview").toFile()) { 1_000_000_000L }
    private fun summary(id: String): JSONObject = ZipFile(Diagnostics.export(id)).use { zip ->
        JSONObject(zip.getInputStream(zip.getEntry("session.json")).bufferedReader().readText())
    }
}
