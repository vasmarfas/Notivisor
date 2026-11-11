package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.control.CapturePacket
import com.vasmarfas.notivisor.core.control.H264
import java.util.concurrent.CopyOnWriteArrayList

class CastHub(
    private val wantKeyframe: () -> Unit,
    private val onAudience: () -> Unit,
) {
    private val viewers = CopyOnWriteArrayList<CastViewer>()

    @Volatile
    private var size: CapturePacket.Size? = null

    @Volatile
    private var config: ByteArray? = null

    @Volatile
    private var lastRequest = 0L

    @Volatile
    private var trouble: String? = null

    val audience: Int get() = viewers.size
    val browsers: Int get() = viewers.count { it.inBrowser }
    val resolution: CapturePacket.Size? get() = size

    fun add(viewer: CastViewer) {
        viewers += viewer
        size?.let(viewer::begin)
        viewer.start()
        trouble?.let { viewer.notice(WebViewer.NOTICE_TROUBLE, it) }
        onAudience()
        requestKeyframe(force = true)
    }

    fun remove(viewer: CastViewer) {
        if (!viewers.remove(viewer)) return
        viewer.close()
        onAudience()
    }

    fun closeAll(browsersOnly: Boolean = false) {
        viewers.filter { !browsersOnly || it.inBrowser }.forEach(::remove)
    }

    fun reset() {
        config = null
        viewers.forEach { it.awaitingKeyframe = true }
    }

    fun notice(code: Byte, text: String = "") {
        trouble = text.takeIf { code == WebViewer.NOTICE_TROUBLE }
        viewers.forEach { it.notice(code, text) }
    }

    fun refresh() = requestKeyframe(force = false)

    fun resize(next: CapturePacket.Size) {
        val resized = size.let { it != null && it != next }
        size = next
        if (resized) config = null
        viewers.forEach { viewer ->
            if (resized && !viewer.survivesResize) {
                viewer.outgrown = true
                remove(viewer)
            } else {
                if (resized) viewer.awaitingKeyframe = true
                viewer.begin(next)
            }
        }
    }

    fun config(bytes: ByteArray) {
        config = bytes
        val at = now()
        viewers.forEach { if (!it.awaitingKeyframe) it.config(bytes, at) }
    }

    fun frame(bytes: ByteArray) {
        val key = H264.isKeyFrame(bytes)
        val parameters = config
        val at = now()
        var starved = false
        viewers.forEach { viewer ->
            if (viewer.awaitingKeyframe) {
                if (!key || parameters == null) {
                    starved = true
                    return@forEach
                }
                viewer.awaitingKeyframe = false
                viewer.config(parameters, at)
            }
            if (!viewer.video(bytes, at)) starved = true
        }
        if (starved) requestKeyframe(force = false)
    }

    fun sound(bytes: ByteArray) {
        val at = now()
        viewers.forEach { if (!it.awaitingKeyframe) it.sound(bytes, at) }
    }

    private fun requestKeyframe(force: Boolean) {
        val now = System.nanoTime()
        if (!force && now - lastRequest < KEYFRAME_REQUEST_NS) return
        lastRequest = now
        wantKeyframe()
    }

    private fun now(): Long = System.nanoTime() / 1_000

    private companion object {
        const val KEYFRAME_REQUEST_NS = 500_000_000L
    }
}
