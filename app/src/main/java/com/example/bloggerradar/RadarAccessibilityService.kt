package com.example.bloggerradar

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍服务：监听小红书(com.xingin.xhs)界面变化，
 * 扫描屏幕上的所有文字，与名单匹配后用悬浮层高亮
 */
class RadarAccessibilityService : AccessibilityService() {

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val SCAN_DELAY_MS = 250L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayView? = null
    private var windowManager: WindowManager? = null

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
            return
        }
        // 防抖：滚动/刷新时事件密集，250ms 后统一扫描一次
        handler.removeCallbacks(scanRunnable)
        handler.postDelayed(scanRunnable, SCAN_DELAY_MS)
    }

    override fun onInterrupt() {
        overlay?.setMatches(emptyList())
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
        val view = overlay ?: return
        if (!Prefs.isEnabled(this)) {
            view.setMatches(emptyList())
            return
        }
        val names = Prefs.loadNames(this)
        if (names.isEmpty()) {
            view.setMatches(emptyList())
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName != XHS_PACKAGE) {
            view.setMatches(emptyList())
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
        view.setMatches(dedup)
    }

    /** 深度优先遍历节点树，收集「文本命中名单且可见」的节点屏幕坐标 */
    private fun collect(
        node: AccessibilityNodeInfo?,
        names: List<String>,
        out: MutableList<OverlayView.Match>,
        visited: MutableSet<AccessibilityNodeInfo>
    ) {
        if (node == null || !visited.add(node)) return
        try {
            val text = node.text?.toString()
            if (!text.isNullOrBlank() && node.isVisibleToUser) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (rect.width() > 0 && rect.height() > 0) {
                    val hit = names.firstOrNull { Matcher.isMatch(text, it) }
                    if (hit != null) out.add(OverlayView.Match(hit, rect))
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
