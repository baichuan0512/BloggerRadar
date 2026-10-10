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

/**
 * 无障碍服务：监听小红书(com.xingin.xhs)界面，
 * 扫描所有窗口的文字，与名单匹配后用悬浮层高亮；
 * 通过「点赞成功/收藏成功」提示气泡 + 点击事件双通道统计（每账号每日上限3次）
 */
class RadarAccessibilityService : AccessibilityService() {

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val SCAN_DELAY_MS = 150L
        private const val PERIODIC_MS = 500L
        private const val RECORD_DEBOUNCE_MS = 3000L
        private const val SUCCESS_LIKE = "点赞成功"
        private const val SUCCESS_COLLECT = "收藏成功"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayView? = null
    private var windowManager: WindowManager? = null

    /** 当前屏幕上处于详情页/卡片的作者（取最靠上的命中），用于给点赞收藏归类 */
    private var currentBlogger: String? = null
    private var lastRecordAt = 0L
    private var lastEventScanAt = 0L

    private val scanRunnable = Runnable { scanScreen() }

    /** 周期扫描兜底：详情页/发现页加载完后可能不再发事件，靠定时器保证高亮及时出现 */
    private val periodicRunnable = object : Runnable {
        override fun run() {
            scanScreen()
            handler.postDelayed(this, PERIODIC_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addOverlay()
        handler.postDelayed(periodicRunnable, PERIODIC_MS)
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
        // 页面切换时立即清掉旧高亮，避免绿框残留盖在错误位置
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlay?.setMatches(emptyList())
        }
        // 通道1：点击事件（部分机型/控件有效）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event.source)
        }
        // 通道2：事件自带文本里出现「点赞成功/收藏成功」提示气泡
        detectSuccessText(event.text)
        // 立即扫描（150ms 节流），另有 500ms 周期扫描兜底
        val now = SystemClock.elapsedRealtime()
        if (now - lastEventScanAt >= 150L) {
            lastEventScanAt = now
            scanScreen()
        } else {
            handler.removeCallbacks(scanRunnable)
            handler.postDelayed(scanRunnable, SCAN_DELAY_MS)
        }
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

    /** 从事件文本里识别「点赞成功/收藏成功」，命中即记录（带去抖防重复计数） */
    private fun detectSuccessText(texts: List<CharSequence>?) {
        texts ?: return
        for (t in texts) {
            val s = t?.toString() ?: continue
            if (s.contains(SUCCESS_COLLECT)) {
                recordAction(StatsStore.ACTION_COLLECT)
                return
            }
            if (s.contains(SUCCESS_LIKE)) {
                recordAction(StatsStore.ACTION_LIKE)
                return
            }
        }
    }

    /** 通道1备用：判断被点击的节点（含祖先）是不是 赞/收藏 按钮 */
    private fun handleClick(node: AccessibilityNodeInfo?) {
        var cur = node
        var depth = 0
        while (cur != null && depth < 4) {
            val desc = (cur.text?.toString() ?: "") + (cur.contentDescription?.toString() ?: "")
            val action = when {
                desc.contains("取消收藏") || desc.contains("取消赞") || desc.contains("取消点赞") -> null // 取消，不统计
                desc.contains("收藏") -> StatsStore.ACTION_COLLECT
                desc.contains("赞") -> StatsStore.ACTION_LIKE
                else -> null
            }
            if (action != null) {
                recordAction(action)
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

    private fun recordAction(action: String) {
        val blogger = currentBlogger ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecordAt < RECORD_DEBOUNCE_MS) return // 同一次操作的提示可能停留几秒，去抖
        lastRecordAt = now
        val account = Prefs.currentAccount(this)
        val ok = StatsStore.record(this, blogger, account, action)
        if (ok) {
            scanScreen()
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

        val matches = ArrayList<OverlayView.Match>()
        val visited = HashSet<AccessibilityNodeInfo>()
        var anyMatchWindow = false

        // 扫描所有窗口（发现页瀑布流可能在独立窗口层，rootInActiveWindow 拿不到）
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) {
                w.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        val active = rootInActiveWindow
        if (active != null && roots.none { it.packageName == active.packageName && it == active }) {
            roots.add(active)
        }
        if (roots.isEmpty()) {
            view.setMatches(emptyList())
            return
        }
        for (root in roots) {
            if (root.packageName != XHS_PACKAGE) continue
            anyMatchWindow = true
            collect(root, names, matches, visited)
        }
        if (!anyMatchWindow) {
            view.setMatches(emptyList())
            currentBlogger = null
            return
        }

        // 按矩形去重
        val dedup = ArrayList<OverlayView.Match>()
        for (m in matches) {
            if (dedup.none { it.rect == m.rect }) dedup.add(m)
        }
        // 计算当日上限提示
        val account = Prefs.currentAccount(this)
        val day = StatsStore.today()
        val withWarn = dedup.map { m ->
            val warn = StatsStore.reachedLimit(this, m.name, account, day)
            OverlayView.Match(m.name, m.rect, warn)
        }
        // 当前博主 = 最靠上的命中（详情页作者通常在顶部）
        currentBlogger = withWarn.minByOrNull { it.rect.top }?.name
        view.setMatches(withWarn)
    }

    /** 深度优先遍历节点树，收集「文本/描述命中名单且可见」的节点屏幕坐标 */
    private fun collect(
        node: AccessibilityNodeInfo?,
        names: List<String>,
        out: MutableList<OverlayView.Match>,
        visited: MutableSet<AccessibilityNodeInfo>
    ) {
        if (node == null || !visited.add(node)) return
        try {
            // 同时检查 text 与 contentDescription（发现页卡片作者名常在 contentDescription 里）
            val candidates = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            )
            if (node.isVisibleToUser) {
                for (raw in candidates) {
                    if (raw.isNullOrBlank()) continue
                    // 「点赞成功/收藏成功」气泡不参与高亮
                    if (raw.contains(SUCCESS_LIKE) || raw.contains(SUCCESS_COLLECT)) continue
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val hit = names.firstOrNull { Matcher.isMatch(raw, it) } ?: continue
                        out.add(OverlayView.Match(hit, rect))
                        break
                    }
                }
            }
            for (i in 0 until node.childCount) {
                collect(node.getChild(i), names, out, visited)
            }
        } catch (_: Exception) {
            // 个别节点可能已失效，忽略
        } finally {
            node.recycle()
        }
    }
}
