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
 * 全屏透明悬浮层：在匹配到的博主昵称位置画 高亮框 + 名字角标
 * 使用 TYPE_ACCESSIBILITY_OVERLAY，无需悬浮窗权限
 * - 正常：绿色 ✓
 * - 当日该账号已达上限(3次)：橙色 ⚠，提醒不要再点
 */
class OverlayView(context: Context) : View(context) {

    data class Match(val name: String, val rect: Rect, val warn: Boolean = false)

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val paintFillOk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#3310B981")
    }
    private val paintStrokeOk = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FF10B981")
        strokeWidth = dp(3f)
    }
    private val paintFillWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33F59E0B")
    }
    private val paintStrokeWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#FFF59E0B")
        strokeWidth = dp(3f)
    }
    private val paintBadgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E610B981")
    }
    private val paintBadgeBgWarn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6F59E0B")
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
            val fill = if (m.warn) paintFillWarn else paintFillOk
            val stroke = if (m.warn) paintStrokeWarn else paintStrokeOk
            canvas.drawRoundRect(r, dp(8f), dp(8f), fill)
            canvas.drawRoundRect(r, dp(8f), dp(8f), stroke)

            // 角标：框上方显示「✓ 名字」(正常) 或「⚠ 已达上限」(warn)
            val label = if (m.warn) "⚠ 已达上限" else "✓ ${m.name.trim()}"
            val bg = if (m.warn) paintBadgeBgWarn else paintBadgeBg
            val tw = paintBadgeText.measureText(label)
            val padH = dp(8f)
            val badgeH = dp(22f)
            var left = r.left
            var top = r.top - badgeH - dp(4f)
            if (top < 0f) top = r.top + dp(4f)
            left = max(0f, min(left, width - tw - padH * 2))
            val badge = RectF(left, top, left + tw + padH * 2, top + badgeH)
            canvas.drawRoundRect(badge, dp(6f), dp(6f), bg)
            canvas.drawText(label, badge.left + padH, badge.top + badgeH / 2 - (paintBadgeText.ascent() + paintBadgeText.descent()) / 2, paintBadgeText)
        }
    }
}
