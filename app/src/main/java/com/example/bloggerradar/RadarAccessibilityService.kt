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
 * 扫描所有「活跃」窗口的文字，与名单匹配后用悬浮层高亮；
 * 统计双通道：屏幕上的「点赞成功/收藏成功」气泡 + 点击事件（每账号每日上限3次）
 *
 * 性能设计：所有扫描统一走 200ms 节拍调度器（最高每秒5次），
 * 且每次扫描有节点数上限，避免拖慢小红书
 */
class RadarAccessibilityService : AccessibilityService() {

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val TICK_MS = 200L          // 调度器节拍
        private const val PERIODIC_MS = 800L      // 无事件时的兜底扫描间隔
        private const val RECORD_DEBOUNCE_MS = 3000L
        private const val MAX_NODES = 3000        // 单次扫描节点数上限（防卡顿）
        private const val SUCCESS_LIKE = "点赞成功"
        private const val SUCCESS_COLLECT = "收藏成功"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayView? = null
    private var windowManager: WindowManager? = null

    /** 当前屏幕上处于详情页/卡片的作者（取最靠上的命中），用于给点赞收藏归类 */
    private var currentBlogger: String? = null
    private var lastRecordAt = 0L

    private var scanPending = false
    private var lastPeriodicScan = 0L

    /** 扫描过程中发现的「成功气泡」动作，扫描结束后统一处理 */
    private var pendingAction: String? = null

    /** 统一节拍调度器：有请求立即扫（受节拍限制），无事件时 800ms 兜底扫一次 */
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
        // 页面切换时立即清掉旧高亮，避免绿框残留盖在错误位置
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlay?.setMatches(emptyList())
            scanPending = true
        }
        // 通道1：点击事件（部分机型/控件有效）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event.source)
        }
        // 通道2：事件自带文本里出现「点赞成功/收藏成功」提示气泡
        detectSuccessText(event.text)
        // 请求扫描（由节拍调度器合并执行）
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

    /** 从事件文本里识别「点赞成功/收藏成功」 */
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
        if (now - lastRecordAt < RECORD_DEBOUNCE_MS) return // 同一次操作的提示可能停留几秒，去抖
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

        // 只扫「活跃」窗口：避免扫到已退出页面残留的旧窗口（鬼影的来源）
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
            if (root.packageName != XHS_PACKAGE) continue
            foundXhs = true
            collect(root, names, matches, visited, budget, screenW, screenH)
        }
        if (!foundXhs) {
            view.setMatches(emptyList())
            currentBlogger = null
            pendingAction = null
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

        // 扫描中发现了成功气泡 → 记录统计
        pendingAction?.let { tryRecord(it) }
        pendingAction = null
    }

    /** 深度优先遍历节点树，收集「文本/描述命中名单且可见」的节点屏幕坐标 */
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
            // 同时检查 text 与 contentDescription（发现页卡片作者名常在 contentDescription 里）
            val candidates = listOfNotNull(
                node.text?.toString(),
                node.contentDescription?.toString()
            )
            if (candidates.isNotEmpty() && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                // 只保留屏幕内的有效矩形（过滤屏幕外/退化节点，去鬼影）
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
            // 个别节点可能已失效，忽略
        } finally {
            node.recycle()
        }
    }
}
