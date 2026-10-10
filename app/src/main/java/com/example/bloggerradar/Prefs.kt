package com.example.bloggerradar

import android.content.Context
import org.json.JSONArray

/** 名单、开关、账号的本地存储（SharedPreferences，仅手机本地） */
object Prefs {

    private const val FILE = "radar_prefs"
    private const val KEY_NAMES = "names_json"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_ACCOUNTS = "accounts_json"
    private const val KEY_CURRENT_ACCOUNT = "current_account"

    fun loadNames(ctx: Context): List<String> {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY_NAMES, "[]") ?: "[]")
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    fun saveNames(ctx: Context, names: List<String>) {
        val arr = JSONArray()
        names.forEach { arr.put(it) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_NAMES, arr.toString()).apply()
    }

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    /** 账号列表（默认主号 / 分身号） */
    fun loadAccounts(ctx: Context): List<String> {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val arr = JSONArray(sp.getString(KEY_ACCOUNTS, "[\"主号\",\"分身号\"]") ?: "[\"主号\",\"分身号\"]")
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) list.add(arr.getString(i))
        return list
    }

    fun saveAccounts(ctx: Context, accounts: List<String>) {
        val arr = JSONArray()
        accounts.forEach { arr.put(it) }
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACCOUNTS, arr.toString()).apply()
    }

    fun currentAccount(ctx: Context): String {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return sp.getString(KEY_CURRENT_ACCOUNT, loadAccounts(ctx).firstOrNull() ?: "主号") ?: "主号"
    }

    fun setCurrentAccount(ctx: Context, account: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_CURRENT_ACCOUNT, account).apply()
    }
}

