package com.example.bloggerradar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var swEnabled: Switch

    private val filePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@registerForActivityResult
            importFile(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        swEnabled = findViewById(R.id.swEnabled)
        val btnImport = findViewById<Button>(R.id.btnImport)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnClear = findViewById<Button>(R.id.btnClear)

        btnImport.setOnClickListener {
            filePicker.launch(arrayOf("*/*"))
        }

        btnAccessibility.setOnClickListener {
            Toast.makeText(
                this,
                "在列表中找到「博主雷达」→ 打开开关 → 允许",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnClear.setOnClickListener {
            Prefs.saveNames(this, emptyList())
            refreshStatus()
            Toast.makeText(this, "名单已清空", Toast.LENGTH_SHORT).show()
        }

        swEnabled.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            Prefs.setEnabled(this, checked)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val names = Prefs.loadNames(this)
        val serviceOn = isServiceEnabled()
        swEnabled.isChecked = Prefs.isEnabled(this)
        tvStatus.text = buildString {
            append("📋 名单数量：")
            append(if (names.isEmpty()) "未导入（请先导入 Excel）" else "${names.size} 位博主")
            append('\n')
            append("♿ 无障碍服务：")
            append(if (serviceOn) "✅ 已开启" else "❌ 未开启（点下方按钮开启）")
            append('\n')
            append("🔍 实时高亮：")
            append(
                when {
                    names.isEmpty() || !serviceOn -> "未生效"
                    Prefs.isEnabled(this@MainActivity) -> "✅ 开启中"
                    else -> "⛔ 已关闭（打开上方开关）"
                }
            )
        }
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${RadarAccessibilityService::class.java.canonicalName}"
        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(setting)
        for (s in splitter) {
            if (s.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    private fun importFile(uri: Uri) {
        thread {
            try {
                val name = queryFileName(uri) ?: "list.xls"
                val stream = contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("无法读取文件")
                val names = ExcelParser.parse(stream, name)
                if (names.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "没有解析到昵称，请检查表格内容", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
                Prefs.saveNames(this, names)
                runOnUiThread {
                    refreshStatus()
                    Toast.makeText(this, "✅ 已导入 ${names.size} 位博主", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "导入失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) {
                return c.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
