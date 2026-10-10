package com.example.bloggerradar

/**
 * 昵称匹配器：
 * - 小红书列表页会把超长昵称截断成「xxx…」，所以必须支持截断模糊匹配
 * - 表格里昵称常带多余空格（首尾/全角），统一规范化
 * - 昵称常带 emoji（如「橙宝很甜🌿」「Clara🍊宝宝」），小红书渲染时
 *   emoji 前后可能插入空格或拆成不同编码，因此匹配前剥离所有
 *   非「文字/数字」字符（emoji、符号、空格全部去掉），只比核心文字
 */
object Matcher {

    /** 规范化：去零宽字符、全角空格、剥离 emoji/符号/所有空白，只保留文字与数字，转小写 */
    fun normalize(s: String): String {
        var t = s.replace("\u200b", "")
            .replace("\u200c", "")
            .replace("\u200d", "")
            .replace("\uFEFF", "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
        return t.lowercase()
    }

    /**
     * 判断小红书屏幕上出现的文字 screen 是否匹配名单里的 target
     * （normalize 已剥离 emoji/空格，此处在纯核心文字上做前缀判断）
     */
    fun isMatch(screen: String, target: String): Boolean {
        val s = normalize(screen)
        val n = normalize(target)
        if (s.isEmpty() || n.isEmpty()) return false

        // 完全相等（最常见）
        if (s == n) return true

        // 屏幕昵称被截断：「云朵里的…」省略号已在规范化时被剥掉，
        // 剩下「云朵里的」是名单昵称的前缀（≥2 字防误判）
        if (n.startsWith(s) && s.length >= 2) return true

        // 表格里的昵称本身较短/被截断，屏幕显示的是以它开头的更长名字
        // （要求名单昵称 ≥4 字防误判）
        if (s.startsWith(n) && n.length >= 4) return true

        return false
    }
}
