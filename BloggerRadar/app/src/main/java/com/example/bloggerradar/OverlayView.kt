package com.example.bloggerradar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 全屏透明悬浮层：在匹配到的博主昵称位置画 绿色高亮框 + 名字角标
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限
 */
class OverlayView(context: Context) : View(context) {

    data class Match(val name: String, val rect: Rect)

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3310B981")
    }
    private val paintStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF10B981")
        strokeWidth = dp(3f)
    }
    private val paintBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E610B981")
    }
    private val paintBadgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = dp(13f)
        isFakeBoldText = true
    }

    private var matches: List<Match> = emptyList()

    fun setMatches(list: List<Match>) {
        matches = list
        visibility = if (list.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (m in matches) {
            val r = RectF(m.rect)
            canvas.drawRoundRect(r, dp(8f), dp(8f), paintFill)
            canvas.drawRoundRect(r, dp(8f), dp(8f), paintStroke)

            // 角标：框上方显示「✓ 名字」，放不下则放框内
            val label = "✓ ${m.name.trim()}"
            val tw = paintBadgeText.measureText(label)
            val padH = dp(8f)
            val badgeH = dp(22f)
            var left = r.left
            var top = r.top - badgeH - dp(4f)
            if (top < 0f) top = r.top + dp(4f)
            left = max(0f, min(left, width - tw - padH * 2))
            val badge = RectF(left, top, left + tw + padH * 2, top + badgeH)
            canvas.drawRoundRect(badge, dp(6f), dp(6f), paintBadgeBg)
            canvas.drawText(label, badge.left + padH, badge.top + badgeH / 2 - (paintBadgeText.ascent() + paintBadgeText.descent()) / 2, paintBadgeText)
        }
    }
}
