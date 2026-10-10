package com.example.bloggerradar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 点赞/收藏统计存储（本地 JSON 文件，仅手机本地）
 * 每条记录：博主、账号、动作(like/collect)、日期(yyyy-MM-dd)、时间戳
 */
object StatsStore {

    const val ACTION_LIKE = "like"
    const val ACTION_COLLECT = "collect"

    /** 每个博主每个账号每天 点赞+收藏 上限 */
    const val DAILY_LIMIT = 3

    private const val FILE = "stats.json"

    /** 内存缓存：避免每次扫描都重新读盘+解析整个 stats.json（当天记录越多越慢） */
    private var statsCache: JSONArray? = null
    private var statsCacheValid = false

    private val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)

    fun today(): String {
        val cal = Calendar.getInstance(Locale.CHINA)
        return sdf.format(cal.time)
    }

    private fun file(ctx: Context) = ctx.getFileStreamPath(FILE)

    private fun loadAll(ctx: Context): JSONArray {
        if (statsCacheValid && statsCache != null) return statsCache!!
        val f = file(ctx)
        val arr = if (!f.exists()) JSONArray() else try {
            JSONArray(f.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            JSONArray()
        }
        statsCache = arr
        statsCacheValid = true
        return arr
    }

    private fun saveAll(ctx: Context, arr: JSONArray) {
        file(ctx).writeText(arr.toString(0), Charsets.UTF_8)
        statsCache = arr   // 写后立即更新缓存，后续读取无需重新读盘
        statsCacheValid = true
    }

    /** 记录一次动作，返回 true=记录成功，false=已达当日上限（不记录） */
    fun record(ctx: Context, blogger: String, account: String, action: String): Boolean {
        val day = today()
        val cur = countFor(ctx, blogger, account, day)
        if (cur >= DAILY_LIMIT) return false
        val arr = loadAll(ctx)
        val obj = JSONObject().apply {
            put("b", blogger)
            put("a", account)
            put("act", action)
            put("d", day)
            put("t", System.currentTimeMillis())
        }
        arr.put(obj)
        saveAll(ctx, arr)
        return true
    }

    /** 某博主某账号某日 已记录的 点赞+收藏 总数 */
    fun countFor(ctx: Context, blogger: String, account: String, day: String): Int {
        val arr = loadAll(ctx)
        var n = 0
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("b") == blogger && o.getString("a") == account && o.getString("d") == day) n++
        }
        return n
    }

    /** 是否已到当日上限 */
    fun reachedLimit(ctx: Context, blogger: String, account: String, day: String = today()): Boolean =
        countFor(ctx, blogger, account, day) >= DAILY_LIMIT

    data class Row(
        val blogger: String,
        val account: String,
        val day: String,
        val likes: Int,
        val collects: Int,
        val total: Int
    )

    /** 按 博主×账号×日期 聚合（升序） */
    fun aggregate(ctx: Context): List<Row> {
        val arr = loadAll(ctx)
        val map = LinkedHashMap<Triple<String, String, String>, Pair<Int, Int>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val key = Triple(o.getString("b"), o.getString("a"), o.getString("d"))
            val like = o.getString("act") == ACTION_LIKE
            val (l, c) = map.getOrDefault(key, 0 to 0)
            map[key] = if (like) (l + 1) to c else l to (c + 1)
        }
        return map.entries.sortedWith(compareBy({ it.key.third }, { it.key.first }, { it.key.second }))
            .map { (k, v) -> Row(k.first, k.second, k.third, v.first, v.second, v.first + v.second) }
    }

    /** 清空全部统计（谨慎调用） */
    fun clear(ctx: Context) {
        saveAll(ctx, JSONArray())
    }
}
