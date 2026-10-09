package com.example.bloggerradar

/**
 * 昵称匹配器：
 * - 小红书列表页会把超长昵称截断成「xxx…」，所以必须支持截断模糊匹配
 * - 表格里昵称常带多余空格（首尾/全角），统一规范化
 */
object Matcher {

    /** 规范化：去首尾空格、全角空格转半角、连续空格合并、去零宽字符、转小写 */
    fun normalize(s: String): String {
        var t = s.replace('\u3000', ' ')
            .replace('\u00A0', ' ')
            .replace("\u200b", "")
            .replace("\u200c", "")
            .replace("\u200d", "")
            .replace("\uFEFF", "")
            .trim()
        t = t.replace(Regex("\\s+"), " ")
        return t.lowercase()
    }

    private fun stripEllipsis(s: String): String =
        s.replaceFirst(Regex("(…+|\\.{2,}|｡｡+)$"), "").trim()

    /**
     * 判断小红书屏幕上出现的文字 screen 是否匹配名单里的 target
     */
    fun isMatch(screen: String, target: String): Boolean {
        val s = normalize(screen)
        val n = normalize(target)
        if (s.isEmpty() || n.isEmpty()) return false

        // 完全相等（最常见）
        if (s == n) return true

        // 屏幕昵称被截断：「云朵里的…」→「云朵里的」是名单昵称的前缀
        val s2 = stripEllipsis(s)
        if (s2 != s && s2.length >= 2 && n.startsWith(s2)) return true

        // 表格里的昵称本身被截断：「奕珩不是一」→ 屏幕全名以它开头（要求≥4字防误判）
        val n2 = stripEllipsis(n)
        if (s.startsWith(n2) && n2.length >= 4) return true

        // 表格昵称带省略号
        if (n2 != n && n2.length >= 2 && s.startsWith(n2)) return true

        return false
    }
}
