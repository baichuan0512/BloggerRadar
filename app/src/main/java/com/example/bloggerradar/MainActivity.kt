package com.example.bloggerradar

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var swEnabled: Switch
    private lateinit var spinnerAccount: Spinner
    private lateinit var tvToday: TextView

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
        spinnerAccount = findViewById(R.id.spinnerAccount)
        tvToday = findViewById(R.id.tvToday)
        val btnImport = findViewById<Button>(R.id.btnImport)
        val btnAccessibility = findViewById<Button>(R.id.btnAccessibility)
        val btnClear = findViewById<Button>(R.id.btnClear)
        val btnAddAccount = findViewById<Button>(R.id.btnAddAccount)
        val btnExport = findViewById<Button>(R.id.btnExport)

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

        btnAddAccount.setOnClickListener { showAddAccountDialog() }

        btnExport.setOnClickListener { exportExcel() }

        swEnabled.setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            Prefs.setEnabled(this, checked)
        }

        setupAccountSpinner()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshToday()
        syncAccountSpinner()
    }

    private fun setupAccountSpinner() {
        val accounts = Prefs.loadAccounts(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, accounts)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAccount.adapter = adapter
        spinnerAccount.setSelection(maxOf(0, accounts.indexOf(Prefs.currentAccount(this))))
        spinnerAccount.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>,
                    view: android.view.View?,
                    pos: Int,
                    id: Long
                ) {
                    val acc = parent.getItemAtPosition(pos) as String
                    Prefs.setCurrentAccount(this@MainActivity, acc)
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>) {}
            }
    }

    private fun syncAccountSpinner() {
        val accounts = Prefs.loadAccounts(this)
        val cur = Prefs.currentAccount(this)
        val idx = maxOf(0, accounts.indexOf(cur))
        if (spinnerAccount.selectedItemPosition != idx) {
            spinnerAccount.setSelection(idx)
        } else {
            // 数据可能变化，重设 adapter
            val adapter = spinnerAccount.adapter as? ArrayAdapter<String>
            if (adapter == null || adapter.count != accounts.size) {
                val a = ArrayAdapter(this, android.R.layout.simple_spinner_item, accounts)
                a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinnerAccount.adapter = a
                spinnerAccount.setSelection(idx)
            }
        }
    }

    private fun showAddAccountDialog() {
        val input = EditText(this)
        input.hint = "如：分身号B"
        AlertDialog.Builder(this)
            .setTitle("新增账号")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val list = Prefs.loadAccounts(this).toMutableList()
                    if (!list.contains(name)) {
                        list.add(name)
                        Prefs.saveAccounts(this, list)
                    }
                    Prefs.setCurrentAccount(this, name)
                    setupAccountSpinner()
                    Toast.makeText(this, "已切换到：$name", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
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
            append("\n💡 当前账号：")
            append(Prefs.currentAccount(this))
        }
    }

    private fun refreshToday() {
        val rows = StatsStore.aggregate(this).filter { it.day == StatsStore.today() }
        if (rows.isEmpty()) {
            tvToday.text = "今日统计：暂无（打开小红书给名单里的博主点赞/收藏即自动记录）"
            return
        }
        val sb = StringBuilder("今日统计（${rows.size} 条记录）：\n")
        for (r in rows) {
            sb.append("· ${r.blogger}（${r.account}）：赞${r.likes} 藏${r.collects}")
            if (r.total >= StatsStore.DAILY_LIMIT) sb.append(" ⚠已达上限")
            sb.append('\n')
        }
        tvToday.text = sb.toString().trimEnd()
    }

    private fun exportExcel() {
        val rows = StatsStore.aggregate(this)
        if (rows.isEmpty()) {
            Toast.makeText(this, "还没有统计数据可导出", Toast.LENGTH_SHORT).show()
            return
        }
        thread {
            try {
                val file = ExcelExporter.export(this, rows)
                runOnUiThread {
                    shareFile(file)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareFile(file: java.io.File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.ms-excel"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "导出 Excel 到"))
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
