package com.example.bloggerradar

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashSet
import java.util.Locale
import kotlin.math.abs

/**
 * 无障碍服务：监听小红书(com.xingin.xhs)界面，
 * 扫描所有「活跃」窗口的文字，与名单匹配后用悬浮层高亮；
 * 统计双通道：屏幕上的「点赞成功/收藏成功」气泡 + 点击事件（每账号每日上限3次）
 *
 * 性能设计：所有扫描统一走 200ms 节拍调度器（最高每秒5次），
 * 且每次扫描有节点数上限，避免拖慢小红书
 *
 * === 发现页（瀑布流）支持说明 ===
 * v1.1 起：命中的昵称节点会向上寻找「网格卡片」祖先，把整张封面卡片框起来。
 * 卡片判定完全不依赖列数，靠「父容器里有 >=2 个宽度接近的兄弟节点」来判断，
 * 所以双瀑布、三瀑布、单列都能自适应。
 */
class RadarAccessibilityService : AccessibilityService() {

    companion object {
        private const val XHS_PACKAGE = "com.xingin.xhs"
        private const val TICK_MS = 200L          // 调度器节拍
        private const val PERIODIC_MS = 800L      // 无事件时的兜底扫描间隔
        private const val RECORD_DEBOUNCE_MS = 3000L
        private const val MAX_NODES = 6000        // 单次扫描节点数上限（防卡顿）
        private const val SUCCESS_LIKE = "点赞成功"
        private const val SUCCESS_COLLECT = "收藏成功"

        /** 卡片宽度占屏幕宽度的有效区间（三瀑布约 0.30，双瀑布约 0.47） */
        private const val CARD_MIN_W_RATIO = 0.18f
        private const val CARD_MAX_W_RATIO = 0.80f
        /** 兄弟节点宽度差在这个比例内就算「同一列的卡片」 */
        private const val SIBLING_W_TOLERANCE = 0.15f

        /** 诊断：MainActivity 置 true，服务在下一拍抓取节点树并广播结果 */
        const val ACTION_DUMP_DONE = "com.example.bloggerradar.ACTION_DUMP_DONE"
        @Volatile
        var dumpRequested = false
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
        // 诊断抓取：点过「抓取节点」后，等小红书真正到前台（收到它的第一个事件）再抓，
        // 保证抓到的一定是发现页/当前页的节点树，而不是雷达自己的窗口
        if (dumpRequested) {
            dumpRequested = false
            dumpNodes()
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

    /** 诊断：把当前小红书所有窗口的「有文字/描述」节点导出成 txt，用于分析发现页结构 */
    private fun dumpNodes() {
        try {
            val sb = StringBuilder()
            sb.append("=== 博主雷达 节点侦察 ===\n")
            sb.append("时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())).append('\n')
            val dm = resources.displayMetrics
            sb.append("屏幕: ${dm.widthPixels}x${dm.heightPixels}\n\n")

            val roots = ArrayList<AccessibilityNodeInfo>()
            try {
                for (w in windows) {
                    if (!w.isActive) continue
                    w.root?.let { roots.add(it) }
                }
            } catch (_: Exception) {
            }
            val active = rootInActiveWindow
            if (active != null && roots.none { it == active }) roots.add(active)

            if (roots.isEmpty()) {
                sb.append("（未抓到任何窗口：请确认小红书在前台、且博主雷达无障碍服务已开启）\n")
            }

            val visited = HashSet<AccessibilityNodeInfo>()
            val counter = intArrayOf(0)
            val budget = intArrayOf(8000)
            for (root in roots) {
                val pkg = root.packageName?.toString()
                sb.append("\n----- 窗口 root: package=").append(pkg).append(" -----\n")
                if (pkg != XHS_PACKAGE) {
                    sb.append("（非小红书窗口，已跳过详情）\n")
                    root.recycle()
                    continue
                }
                dumpCollect(root, sb, visited, budget, counter)
            }
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: cacheDir
            val file = File(dir, "bloggerradar_nodes.txt")
            file.writeText(sb.toString())

            val intent = Intent(ACTION_DUMP_DONE).apply {
                putExtra("path", file.absolutePath)
                putExtra("count", counter[0])
            }
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(ACTION_DUMP_DONE).apply {
                    putExtra("path", "")
                    putExtra("count", 0)
                }
                applicationContext.sendBroadcast(intent)
            } catch (_: Exception) {
            }
        }
    }

    /** 只输出有 text 或 contentDescription 的节点，帮助定位发现页作者名所在节点 */
    private fun dumpCollect(
        node: AccessibilityNodeInfo?,
        sb: StringBuilder,
        visited: MutableSet<AccessibilityNodeInfo>,
        budget: IntArray,
        counter: IntArray
    ) {
        if (node == null || budget[0] <= 0) return
        if (!visited.add(node)) return
        budget[0]--
        try {
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val hasText = !text.isNullOrBlank()
            val hasDesc = !desc.isNullOrBlank()
            if (hasText || hasDesc) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
                val vid = node.viewIdResourceName ?: ""
                val imp = try { node.isImportantForAccessibility } catch (_: Exception) { false }
                val vis = node.isVisibleToUser
                val t = (text ?: "").replace("\n", " ").take(80)
                val d = (desc ?: "").replace("\n", " ").take(80)
                sb.append("#").append(counter[0]++).append(' ')
                    .append('[').append(cls).append("] vis=").append(vis)
                    .append(" imp=").append(imp)
                    .append(" rect=[${rect.left},${rect.top},${rect.right},${rect.bottom}]")
                if (vid.isNotBlank()) sb.append(" id=").append(vid)
                if (hasText) sb.append(" text=\"").append(t).append('"')
                if (hasDesc) sb.append(" desc=\"").append(d).append('"')
                sb.append('\n')
            }
            for (i in 0 until node.childCount) {
                if (budget[0] <= 0) break
                dumpCollect(node.getChild(i), sb, visited, budget, counter)
            }
        } catch (_: Exception) {
        } finally {
            node.recycle()
        }
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
            Toast.makeText(this, "「$blogger」今日($account)已达3次上限", Toast.LENGTH_LONG).show()
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
        // 悬浮层还没测量出来时用真实屏幕尺寸兜底，否则卡片判定会全部失效
        if (screenW <= 1 || screenH <= 1) {
            val dm = resources.displayMetrics
            screenW = dm.widthPixels.coerceAtLeast(1)
            screenH = dm.heightPixels.coerceAtLeast(1)
        }

        for (root in roots) {
            if (root.packageName == XHS_PACKAGE) {
                foundXhs = true
                collect(root, names, matches, visited, budget, screenW, screenH)
            } else {
                // 关键：非小红书窗口根节点也要 recycle，否则 AccessibilityNodeInfo 池
                // 长期运行持续泄漏，最终池耗尽、每次 getChild 都新建节点 → 越扫越慢
                root.recycle()
            }
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
            // 注意：这里不再用 isVisibleToUser 做硬过滤。
            // 小红书瀑布流的卡片在 RecyclerView 里滚动时，这个标志经常短暂为 false，
            // 一旦过滤掉，发现页就整片扫不到，只剩详情页能命中。改为只信屏幕坐标。
            if (candidates.isNotEmpty()) {
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
                        // 发现页：向上找网格卡片，把整张封面框起来；详情页/其他场景保持原样
                        val target = cardRectFor(node, rect, screenW, screenH)
                        out.add(OverlayView.Match(hit, target))
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

    /**
     * 从命中的昵称节点向上找「瀑布流卡片」容器，返回卡片矩形；找不到就返回昵称自身矩形。
     *
     * 判定卡片不靠写死列数，而是靠结构特征：某一级祖先的宽度占屏 18%~80%，
     * 且它的父容器里存在 >=2 个宽度几乎相同的兄弟 —— 这就是网格里的一张卡片。
     * 因此双瀑布（≈47%）、三瀑布（≈31%）、甚至单列都能自动适配。
     *
     * 往上遇到宽度 > 80% 的祖先（整行/整屏）立即停止，避免把详情页顶栏整条框进去。
     */
    private fun cardRectFor(
        node: AccessibilityNodeInfo,
        selfRect: Rect,
        screenW: Int,
        screenH: Int
    ): Rect {
        var cur: AccessibilityNodeInfo? = try {
            node.parent
        } catch (_: Exception) {
            null
        }
        var depth = 0
        try {
            while (depth < 5) {
                val mine = cur ?: return selfRect
                cur = null // 所有权移交给 mine，下面统一回收
                val r = Rect()
                var isCard = false
                try {
                    mine.getBoundsInScreen(r)
                    val wRatio = r.width().toFloat() / screenW
                    if (wRatio > CARD_MAX_W_RATIO || r.height() > screenH * 0.9f) {
                        // 已经到整屏/整行了，没必要再往上
                        return selfRect
                    }
                    if (wRatio >= CARD_MIN_W_RATIO && r.height() > selfRect.height() * 1.4f) {
                        isCard = isGridItem(mine, r)
                    }
                    // 先把下一跳拿到手，再回收当前节点
                    cur = try {
                        mine.parent
                    } catch (_: Exception) {
                        null
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        mine.recycle()
                    } catch (_: Exception) {
                    }
                }
                if (isCard) return r
                depth++
            }
            return selfRect
        } finally {
            try {
                cur?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** 该节点是否为网格中的一张卡片：父容器里至少 2 个宽度接近的兄弟 */
    private fun isGridItem(item: AccessibilityNodeInfo, itemRect: Rect): Boolean {
        val targetW = itemRect.width()
        if (targetW <= 0) return false
        var parent: AccessibilityNodeInfo? = null
        try {
            val p: AccessibilityNodeInfo = item.parent ?: return false
            parent = p
            var similar = 0
            val n = p.childCount
            for (i in 0 until n) {
                var c: AccessibilityNodeInfo? = null
                try {
                    val child: AccessibilityNodeInfo = p.getChild(i) ?: continue
                    c = child
                    val cr = Rect()
                    child.getBoundsInScreen(cr)
                    if (cr.width() > 0 && abs(cr.width() - targetW) <= targetW * SIBLING_W_TOLERANCE) {
                        similar++
                    }
                } catch (_: Exception) {
                } finally {
                    try {
                        c?.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
            return similar >= 2
        } catch (_: Exception) {
            return false
        } finally {
            try {
                parent?.recycle()
            } catch (_: Exception) {
            }
        }
    }
}
