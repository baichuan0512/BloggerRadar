package com.example.bloggerradar

import android.content.Context
import jxl.Workbook
import jxl.write.Label
import jxl.write.WritableWorkbook
import java.io.File

/** 把统计结果导出为 .xls（jxl），保存到 app 外部文件目录，便于分享 */
object ExcelExporter {

    fun export(ctx: Context, rows: List<StatsStore.Row>): File {
        val dir = ctx.getExternalFilesDir("export") ?: ctx.filesDir
        dir.mkdirs()
        val file = File(dir, "博主数据_${StatsStore.today()}.xls")
        var wb: WritableWorkbook? = null
        try {
            wb = Workbook.createWorkbook(file)
            val sheet = wb.createSheet("统计", 0)
            val header = arrayOf("日期", "博主", "账号", "点赞数", "收藏数", "合计")
            header.forEachIndexed { i, h -> sheet.addCell(Label(i, 0, h)) }
            rows.forEachIndexed { r, row ->
                sheet.addCell(Label(0, r + 1, row.day))
                sheet.addCell(Label(1, r + 1, row.blogger))
                sheet.addCell(Label(2, r + 1, row.account))
                sheet.addCell(Label(3, r + 1, row.likes.toString()))
                sheet.addCell(Label(4, r + 1, row.collects.toString()))
                sheet.addCell(Label(5, r + 1, row.total.toString()))
            }
            wb.write()
        } finally {
            wb?.close()
        }
        return file
    }
}
