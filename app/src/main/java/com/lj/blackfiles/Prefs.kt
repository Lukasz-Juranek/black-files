package com.lj.blackfiles

import android.content.Context
import androidx.core.content.edit

/** Small persisted state: last folder, hidden files and sort order. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("files", Context.MODE_PRIVATE)

    fun lastPath(c: Context): String? = sp(c).getString("path", null)
    fun setLastPath(c: Context, path: String?) = sp(c).edit { putString("path", path) }

    fun showHidden(c: Context) = sp(c).getBoolean("hidden", false)
    fun setShowHidden(c: Context, on: Boolean) = sp(c).edit { putBoolean("hidden", on) }

    fun sort(c: Context): Sort = Sort.entries.firstOrNull { it.name == sp(c).getString("sort", null) } ?: Sort.NAME
    fun sortDescending(c: Context) = sp(c).getBoolean("desc", false)
    fun setSort(c: Context, sort: Sort, descending: Boolean) =
        sp(c).edit { putString("sort", sort.name); putBoolean("desc", descending) }
}
