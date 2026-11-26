package com.example.scrollcounter.detection

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SPIKE ONLY: throwaway code for Milestone 1 (revision 2).
 *
 * Changes from revision 1:
 * - Dumps the visible ViewPager subtree (the video area), not the whole window.
 * - Triggers dumps on ViewPager content changes, which is where swipes appear.
 * - Heartbeat every 5 s, so we can see when event delivery stops.
 * - Wall-clock timestamps, so logs line up with the times you swipe.
 *
 * Privacy: text and content descriptions are logged as length + hash only.
 */
class SpikeAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var dumpId = 0
    private var eventCount = 0
    private var lastEventMs = 0L

    private val dumpRunnable = Runnable { dumpPager("debounced") }

    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            val ago = if (lastEventMs == 0L) -1 else SystemClock.elapsedRealtime() - lastEventMs
            Log.i(TAG_HB, "${now()} alive events=$eventCount lastEventAgoMs=$ago")
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG_EVT, "${now()} service connected")
        handler.post(heartbeatRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg !in TIKTOK_PACKAGES) return

        eventCount++
        lastEventMs = SystemClock.elapsedRealtime()
        val className = event.className?.toString() ?: ""
        val isPager = className.contains("ViewPager")

        Log.d(
            TAG_EVT,
            "${now()} n=$eventCount " +
                    "type=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                    "cls=${className.substringAfterLast('.')} " +
                    "count=${event.itemCount} scrollY=${event.scrollY} dY=${event.scrollDeltaY} " +
                    "contentChange=${event.contentChangeTypes} textLen=${event.text.sumOf { it.length }}"
        )

        val shouldDump = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> isPager
            else -> false
        }
        if (shouldDump) {
            handler.removeCallbacks(dumpRunnable)
            handler.postDelayed(dumpRunnable, DUMP_DEBOUNCE_MS)
        }
    }

    /** Dumps the deepest visible ViewPager subtree (the video area), or the root if none. */
    private fun dumpPager(reason: String) {
        val root = rootInActiveWindow ?: run {
            Log.w(TAG_TREE, "${now()} no active window root ($reason)")
            return
        }
        if (root.packageName?.toString() !in TIKTOK_PACKAGES) return

        val pagers = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
        collectPagers(root, 0, pagers)
        val target = pagers.filter { it.second.isVisibleToUser }
            .maxByOrNull { it.first }?.second
        val start = target ?: root

        val id = ++dumpId
        var count = 0
        Log.d(TAG_TREE, "${now()} [$id] dump start ($reason) pagerFound=${target != null}")

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val item = node.collectionItemInfo
            val line = buildString {
                append("[$id] ")
                append("  ".repeat(depth))
                append(node.className?.toString()?.substringAfterLast('.'))
                append(" id=").append(node.viewIdResourceName?.substringAfter(":id/"))
                append(" vis=").append(if (node.isVisibleToUser) 1 else 0)
                append(" sel=").append(if (node.isSelected) 1 else 0)
                append(" foc=").append(if (node.isFocused) 1 else 0)
                append(" scr=").append(if (node.isScrollable) 1 else 0)
                append(" row=").append(item?.rowIndex)
                append(" col=").append(item?.columnIndex)
                append(" b=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
                append(" text=").append(describe(node.text))
                append(" desc=").append(describe(node.contentDescription))
            }
            Log.d(TAG_TREE, line)
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, depth + 1) }
            }
        }

        walk(start, 0)
        Log.d(TAG_TREE, "[$id] dump end nodes=$count capped=${count >= MAX_NODES}")
    }

    /** Collects every ViewPager node with its depth in the tree. */
    private fun collectPagers(
        node: AccessibilityNodeInfo,
        depth: Int,
        out: MutableList<Pair<Int, AccessibilityNodeInfo>>
    ) {
        if (node.className?.toString()?.contains("ViewPager") == true) {
            out.add(depth to node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectPagers(it, depth + 1, out) }
        }
    }

    /** "len:hash" by default, never raw content. */
    private fun describe(cs: CharSequence?): String {
        if (cs.isNullOrEmpty()) return "-"
        val s = cs.toString()
        val hash = s.hashCode().toUInt().toString(16)
        return if (LOG_RAW_TEXT) "'$s'" else "${s.length}:$hash"
    }

    private fun now(): String = clockFmt.format(Date())

    private fun stopTimers() {
        handler.removeCallbacks(dumpRunnable)
        handler.removeCallbacks(heartbeatRunnable)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.w(TAG_EVT, "${now()} onUnbind")
        stopTimers()
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        Log.w(TAG_EVT, "${now()} service interrupted")
    }

    override fun onDestroy() {
        Log.w(TAG_EVT, "${now()} onDestroy")
        stopTimers()
        super.onDestroy()
    }

    private companion object {
        const val TAG_EVT = "TTSpike-EVT"
        const val TAG_TREE = "TTSpike-TREE"
        const val TAG_HB = "TTSpike-HB"
        const val LOG_RAW_TEXT = false
        const val DUMP_DEBOUNCE_MS = 400L
        const val HEARTBEAT_MS = 5000L
        const val MAX_NODES = 1500
        const val MAX_DEPTH = 40
        val TIKTOK_PACKAGES = setOf(
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill"
        )
    }
}