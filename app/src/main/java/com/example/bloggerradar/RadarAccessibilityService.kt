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
 * 无障碍服务：监听小红书(com.xingin.xhs)界面变化，
 * 扫描屏幕上的所有文字，与名单匹配后用悬浮层高亮；
 * 同时检测「点赞 / 收藏」点击，按 博主×账号×日期 统计（每账号每日上限3次）
 */
class RadarAccessibilityService : AccessibilityService() {

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val SCAN_DELAY_MS = 250L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayView? = null
    private var windowManager: WindowManager? = null
    private var lastScanAt = 0L

    /** 当前屏幕上处于详情页/卡片的作者（取最靠上的命中），用于给点赞收藏归类 */
    private var currentBlogger: String? = null

    private val scanRunnable = Runnable { scanScreen() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        addOverlay()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // 只关心小红书的事件
        if (event.packageName != XHS_PACKAGE) {
            overlay?.setMatches(emptyList())
            currentBlogger = null
            return
        }
        // 页面切换（进详情/返回列表）时立即清掉旧高亮，避免绿框残留盖在错误位置
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            overlay?.setMatches(emptyList())
        }
        // 检测点赞/收藏点击
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            handleClick(event.source)
        }
        // 双保险扫描：事件稀疏时立即扫一次（节流 300ms），密集时 250ms 后统一扫
        val now = SystemClock.elapsedRealtime()
        if (now - lastScanAt >= 300L) {
            lastScanAt = now
            scanScreen()
        }
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, SCAN_DELAY_MS)
    }

    /** 判断被点击的节点（含祖先）是不是 赞/收藏 按钮，是则记录统计 */
    private fun handleClick(node: AccessibilityNodeInfo?) {
        var cur = node
        repeat(4) {
            if (cur == null) return
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
        }
        // 遍历完未命中也要回收最后一个节点，避免 AccessibilityNodeInfo 泄漏
        cur?.recycle()
    }

    private fun recordAction(action: String) {
        val blogger = currentBlogger ?: return
        val account = Prefs.currentAccount(this)
        val ok = StatsStore.record(this, blogger, account, action)
        if (ok) {
            // 记录后刷新高亮，让角标立即反映是否达上限
            scanScreen()
            val verb = if (action == StatsStore.ACTION_LIKE) "赞" else "藏"
            Toast.makeText(applicationContext, "已记${verb}：$blogger（$account）", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(applicationContext, "「$blogger」今日($account)已达3次上限", Toast.LENGTH_LONG).show()
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

    private fun scanScreen() {
        lastScanAt = SystemClock.elapsedRealtime()
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
        val root = rootInActiveWindow
        if (root == null || root.packageName != XHS_PACKAGE) {
            view.setMatches(emptyList())
            currentBlogger = null
            return
        }

        val matches = ArrayList<OverlayView.Match>()
        val visited = HashSet<AccessibilityNodeInfo>()
        collect(root, names, matches, visited)

        // 同一个昵称可能命中多条相邻节点，按矩形去重
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
