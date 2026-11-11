package com.vasmarfas.notivisor.headset.core

import com.vasmarfas.notivisor.core.control.CapturePacket
import com.vasmarfas.notivisor.core.control.H264
import com.vasmarfas.notivisor.core.util.BridgeLog
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private fun now() = System.nanoTime() / 1000

internal open class Piece(val type: Byte, val bytes: ByteArray) {
    val stamp = now()
}

internal class Config(bytes: ByteArray, val size: CapturePacket.Size) : Piece(WebViewer.TAG_CONFIG, bytes)

internal class Frame(bytes: ByteArray, val config: Config) : Piece(WebViewer.TAG_VIDEO, bytes) {
    val key = H264.isKeyFrame(bytes)
}

class CastHub(private val wantKeyframe: () -> Unit, private val onAudience: () -> Unit) {

    private val lock = ReentrantLock()
    private val viewers = mutableListOf<CastViewer>()
    private val backlog = ArrayDeque<Piece>()
    private var first = 0L
    private var bytes = 0L
    private var keyframe = -1L
    private var size: CapturePacket.Size? = null
    private var config: Config? = null
    private var notice: Piece? = null
    private var noticeAt = -1L
    private var askedAt = now() - KEYFRAME_GAP_US

    private val end: Long get() = first + backlog.size

    val audience: Int get() = lock.withLock { viewers.size }

    val browsers: Int get() = lock.withLock { viewers.count { it.inBrowser } }

    fun add(viewer: CastViewer) {
        lock.withLock {
            viewer.cursor = if (keyframe >= 0 && viewer.inBrowser) keyframe else end
            if (viewer.inBrowser) viewer.pending = notice?.takeIf { it.bytes[0] == WebViewer.NOTICE_TROUBLE }
            viewers += viewer
            askedAt = now()
        }
        viewer.start(this)
        wantKeyframe()
        onAudience()
    }

    fun remove(viewer: CastViewer) {
        val present = lock.withLock { viewers.remove(viewer) }
        viewer.close()
        if (present) onAudience()
    }

    fun closeAll(browsersOnly: Boolean = false) {
        val gone = lock.withLock { viewers.filter { !browsersOnly || it.inBrowser }.also { viewers -= it } }
        gone.forEach(CastViewer::close)
        if (gone.isNotEmpty()) onAudience()
    }

    fun reset() {
        lock.withLock { invalidate() }
    }

    fun notice(code: Byte, text: String = "") {
        val piece = Piece(WebViewer.TAG_NOTICE, byteArrayOf(code) + text.toByteArray(Charsets.UTF_8))
        val ask = lock.withLock {
            notice = piece
            noticeAt = end
            append(piece)
        }
        if (ask) wantKeyframe()
    }

    fun refresh() {
        if (lock.withLock { due() }) wantKeyframe()
    }

    fun resize(next: CapturePacket.Size) {
        val outgrown = lock.withLock {
            if (next == size) return
            size = next
            invalidate()
            viewers.filter { !it.survivesResize && it.config != null }
                .onEach { it.outgrown = true }
                .also { viewers -= it }
        }
        outgrown.forEach(CastViewer::close)
        if (outgrown.isNotEmpty()) onAudience()
    }

    fun config(bytes: ByteArray) {
        lock.withLock {
            val size = size ?: return
            if (config?.bytes?.contentEquals(bytes) != true) config = Config(bytes, size)
        }
    }

    fun frame(bytes: ByteArray) {
        if (lock.withLock { append(Frame(bytes, config ?: return)) }) wantKeyframe()
    }

    fun sound(bytes: ByteArray) {
        if (lock.withLock { append(Piece(WebViewer.TAG_SOUND, bytes)) }) wantKeyframe()
    }

    internal fun next(viewer: CastViewer): Piece? = lock.withLock {
        if (viewer.cursor < first) {
            BridgeLog.d(SCOPE, "a viewer fell ${first - viewer.cursor} pieces behind, moving it to the newest keyframe")
            skip(viewer, if (keyframe >= 0) keyframe else first)
        }
        viewer.pending?.let {
            viewer.pending = null
            return it
        }
        while (viewer.cursor < end) {
            val at = viewer.cursor++
            val piece = backlog[(at - first).toInt()]
            when {
                piece is Frame -> if (viewer.synced || piece.key) {
                    viewer.synced = true
                    if (piece.config === viewer.config) return piece
                    viewer.config = piece.config
                    viewer.cursor = at
                    return piece.config
                }

                piece.type == WebViewer.TAG_NOTICE -> if (viewer.inBrowser) return piece
                viewer.synced && backlog.last().stamp - piece.stamp <= SOUND_LAG_US -> return piece
            }
        }
        null
    }

    private fun append(piece: Piece): Boolean {
        if (piece is Frame && piece.key) keyframe = end
        backlog.addLast(piece)
        bytes += piece.bytes.size
        val floor = viewers.fold(if (keyframe >= 0) keyframe else end) { low, viewer -> minOf(low, viewer.cursor) }
        while (backlog.isNotEmpty()) {
            val head = backlog.first()
            val full = bytes > MAX_BYTES || piece.stamp - head.stamp > MAX_AGE_US
            if (first >= floor && !full) break
            backlog.removeFirst()
            bytes -= head.bytes.size
            first++
        }
        if (keyframe < first) keyframe = -1
        viewers.forEach(CastViewer::wake)
        return viewers.any { (!it.synced || it.cursor < first) && keyframe < it.cursor } && due()
    }

    private fun invalidate() {
        first = end
        backlog.clear()
        bytes = 0
        keyframe = -1
        config = null
        viewers.forEach { skip(it, first) }
    }

    private fun skip(viewer: CastViewer, to: Long) {
        if (viewer.inBrowser && viewer.cursor <= noticeAt && noticeAt < to) viewer.pending = notice
        viewer.cursor = to
        viewer.synced = false
    }

    private fun due(): Boolean {
        val time = now()
        if (time - askedAt < KEYFRAME_GAP_US) return false
        askedAt = time
        return true
    }

    private companion object {
        const val SCOPE = "casthub"
        const val MAX_BYTES = 6L shl 20
        const val MAX_AGE_US = 3_000_000L
        const val SOUND_LAG_US = 500_000L
        const val KEYFRAME_GAP_US = 500_000L
    }
}
