package com.example.bloggerradar

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class RadarAccessibilityService : AccessibilityService() {

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val TICK_MS = 200L
        private const val PERIODIC_MS = 800L
        private const val RECORD_DEBOUNCE_MS = 3000L
        private const val MAX_NODES = 3000
        private const val SUCCESS_LIKE = "点赞成功"
        private const val SUCCESS_COLLECT = "收藏成功"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayView? = null
    private var windowManager: WindowManager? = null

    private var currentBlogger: String? = null
    private var lastRecordAt = 0L

    private var scanPending = false
    private var lastPeriodicScan = 0L
    private var pendingAction: String? = null

    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (scanPending || now - lastPeriodicScan >= PERIODIC_MS) {
                scanPending = false
                lastPeriodicScan = now
                scanScreen()
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addOverlay()
        handler.postDelayed(ticker, TICK_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (event.packageName != XHS_PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                overlay?.setMatches(emptyList())
                currentBlogger = null
            }
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlay?.setMatches(emptyList())
            scanPending = true
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event.source)
        }
        detectSuccessText(event.text)
        scanPending = true
    }

    override fun onInterrupt() {
        overlay?.setMatches(emptyList())
        currentBlogger = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        super.onDestroy()
    }

    private fun addOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = PixelFormat.TRANSLUCENT
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        val view = OverlayView(this)
        view.visibility = View.INVISIBLE
        wm.addView(view, params)
        windowManager = wm
        overlay = view
    }

    private fun removeOverlay() {
        try {
            overlay?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null
        windowManager = null
    }

    private fun detectSuccessText(texts: List<CharSequence>?) {
        texts ?: return
        for (t in texts) {
            val s = t?.toString() ?: continue
            if (s.contains(SUCCESS_COLLECT)) {
                tryRecord(StatsStore.ACTION_COLLECT)
                return
            }
            if (s.contains(SUCCESS_LIKE)) {
                tryRecord(StatsStore.ACTION_LIKE)
                return
            }
        }
    }

    private fun handleClick(node: AccessibilityNodeInfo?) {
        var cur = node
        var depth = 0
        while (cur != null && depth < 4) {
            val desc = (cur.text?.toString() ?: "") + (cur.contentDescription?.toString() ?: "")
            val action = when {
                desc.contains("取消收藏") || desc.contains("取消赞") || desc.contains("取消点赞") -> null
                desc.contains("收藏") -> StatsStore.ACTION_COLLECT
                desc.contains("赞") -> StatsStore.ACTION_LIKE
                else -> null
            }
            if (action != null) {
                tryRecord(action)
                cur.recycle()
                return
            }
            val parent = cur.parent
            cur.recycle()
            cur = parent
            depth++
        }
        cur?.recycle()
    }

    private fun tryRecord(action: String) {
        val blogger = currentBlogger ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecordAt < RECORD_DEBOUNCE_MS) return
        lastRecordAt = now
        val account = Prefs.currentAccount(this)
        val ok = StatsStore.record(this, blogger, account, action)
        if (ok) {
            scanPending = true
            val verb = if (action == StatsStore.ACTION_LIKE) "赞" else "藏"
            Toast.makeText(applicationContext, "已记${verb}：$blogger（$account）", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(applicationContext, "「$blogger」今日($account)已达3次上限", Toast.LENGTH_LONG).show()
        }
    }

    private fun scanScreen() {
        val view = overlay ?: return
        if (!Prefs.isEnabled(this)) {
            view.setMatches(emptyList())
            currentBlogger = null
            return
        }
        val names = Prefs.loadNames(this)
        if (names.isEmpty()) {
            view.setMatches(emptyList())
            currentBlogger = null
            return
        }

        pendingAction = null
        val matches = ArrayList<OverlayView.Match>()
        val visited = HashSet<AccessibilityNodeInfo>()
        val budget = intArrayOf(MAX_NODES)
        var foundXhs = false

        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                if (!w.isActive) continue
                w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        val active = rootInActiveWindow
        if (active != null && roots.none { it == active }) {
            roots.add(active)
        }

        var screenW = 1
        var screenH = 1
        try {
            screenW = view.width.coerceAtLeast(1)
            screenH = view.height.coerceAtLeast(1)
        } catch (_: Exception) {
        }

        for (root in roots) {
            if (root.packageName == XHS_PACKAGE) {
                foundXhs = true
                collect(root, names, matches, visited, budget, screenW, screenH)
            } else {
                // 关键：非小红书窗口根节点也要 recycle，否则 AccessibilityNodeInfo 池
                // 长期运行持续泄漏，最终池耗尽、每次 getChild 都新建节点 -> 越扫越慢
                root.recycle()
            }
        }
        if (!foundXhs) {
            view.setMatches(emptyList())
            currentBlogger = null
            pendingAction = null
            return
        }

        val dedup = ArrayList<OverlayView.Match>()
        for (m in matches) {
            if (dedup.none { it.rect == m.rect }) dedup.add(m)
        }
        val account = Prefs.currentAccount(this)
        val day = StatsStore.today()
        val withWarn = dedup.map { m ->
            val warn = StatsStore.reachedLimit(this, m.name, account, day)
            OverlayView.Match(m.name, m.rect, warn)
        }
        currentBlogger = withWarn.minByOrNull { it.rect.top }?.name
        view.setMatches(withWarn)

        pendingAction?.let { tryRecord(it) }
        pendingAction = null
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        names: List<String>,
        out: MutableList<OverlayView.Match>,
        visited: MutableSet<AccessibilityNodeInfo>,
        budget: IntArray,
        screenW: Int,
        screenH: Int
    ) {
        if (node == null || budget[0] <= 0) return
        if (!visited.add(node)) return
        budget[0]--
        try {
            val candidates = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            )
            if (candidates.isNotEmpty() && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val onScreen = rect.top < screenH && rect.bottom > 0 &&
                        rect.left < screenW && rect.right > 0 &&
                        rect.width() >= 20 && rect.height() >= 10
                if (onScreen) {
                    for (raw in candidates) {
                        if (raw.isNullOrBlank()) continue
                        if (raw.contains(SUCCESS_LIKE)) {
                            pendingAction = StatsStore.ACTION_LIKE
                            continue
                        }
                        if (raw.contains(SUCCESS_COLLECT)) {
                            pendingAction = StatsStore.ACTION_COLLECT
                            continue
                        }
                        val hit = names.firstOrNull { Matcher.isMatch(raw, it) } ?: continue
                        out.add(OverlayView.Match(hit, rect))
                        break
                    }
                }
            }
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                collect(node.getChild(i), names, out, visited, budget, screenW, screenH)
            }
        } catch (_: Exception) {
        } finally {
            node.recycle()
        }
    }
}
