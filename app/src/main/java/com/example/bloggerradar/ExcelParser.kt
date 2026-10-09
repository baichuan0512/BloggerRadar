package com.example.bloggerradar

import java.io.InputStream

/** 解析用户导入的名单文件（.xls / .csv / .txt），提取所有博主昵称 */
object ExcelParser {

    fun parse(stream: InputStream, fileName: String): List<String> {
        val lower = fileName.lowercase()
        val raw = when {
            lower.endsWith(".xls") -> parseXls(stream)
            lower.endsWith(".csv") || lower.endsWith(".txt") -> parseText(stream)
            lower.endsWith(".xlsx") -> throw IllegalArgumentException(
                "暂不支持 .xlsx。请在电脑上用 Excel 打开 → 另存为 → 选择「Excel 97-2003 (*.xls)」再导入"
            )
            else -> throw IllegalArgumentException("不支持的文件类型：$fileName\n支持 .xls / .csv / .txt")
        }
        // 规范化 + 去重 + 过滤过短项
        val seen = HashSet<String>()
        val result = ArrayList<String>()
        for (item in raw) {
            val n = Matcher.normalize(item)
            if (n.length < 2) continue
            if (seen.add(n)) result.add(item.trim())
        }
        return result
    }

    /** 用 jxl 读取 .xls（Excel 97-2003），扫描所有 sheet 的所有单元格 */
    private fun parseXls(stream: InputStream): List<String> {
        val out = ArrayList<String>()
        stream.use { ins ->
            val wb = jxl.Workbook.getWorkbook(ins)
            wb.use { book ->
                for (sheet in book.sheets) {
                    for (r in 0 until sheet.rows) {
                        for (c in 0 until sheet.columns) {
                            val cell = sheet.getCell(c, r) ?: continue
                            val v = cell.contents?.trim() ?: continue
                            if (v.isNotEmpty()) out.add(v)
                        }
                    }
                }
            }
        }
        return out
    }

    /** 纯文本：按行读，每行再按 逗号/分号/Tab 切分 */
    private fun parseText(stream: InputStream): List<String> {
        val text = stream.readBytes().toString(Charsets.UTF_8)
        val out = ArrayList<String>()
        for (line in text.lineSequence()) {
            for (part in line.split(",", ";", "\t")) {
                val v = part.trim()
                if (v.isNotEmpty()) out.add(v)
            }
        }
        return out
    }
}
