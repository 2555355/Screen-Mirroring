// SPDX-License-Identifier: GPL-3.0-or-later
// Screen-Mirroring - 跨平台手机投屏软件
// Copyright (C) 2025 Screen-Mirroring Contributors
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program.  If not, see <https://www.gnu.org/licenses/>.

package com.screencast.sender

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView

/**
 * 应用列表界面：列出设备上所有可启动的第三方应用，
 * 用户选中后把包名返回给 [MainActivity]，由后者启动该 App 并请求单应用投屏授权。
 *
 * 列表项展示：图标 + 应用名 + 包名，按应用名排序。
 * 不列出系统应用（launcher、设置等），只显示有主 Activity 的用户应用。
 */
class AppListActivity : Activity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LABEL = "label"
    }

    private data class AppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    private val apps = mutableListOf<AppItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_list)

        val listView = findViewById<ListView>(R.id.appList)
        val tvHint = findViewById<TextView>(R.id.tvAppListHint)
        tvHint.text = "选择要投屏的应用\n（之后在系统弹窗中选「单个应用」即可）"

        // 后台加载应用列表，避免阻塞 UI
        Thread {
            val loaded = loadApps()
            runOnUiThread {
                apps.clear()
                apps.addAll(loaded)
                listView.adapter = AppAdapter(apps)
            }
        }.start()

        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val item = apps[position]
            val data = Intent().apply {
                putExtra(EXTRA_PACKAGE, item.packageName)
                putExtra(EXTRA_LABEL, item.label)
            }
            setResult(Activity.RESULT_OK, data)
            finish()
        }
    }

    /** 加载所有有主 Activity 的第三方应用。 */
    private fun loadApps(): List<AppItem> {
        val pm = packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(main, 0)
        val myPkg = packageName
        return resolved
            .filter { it.activityInfo.packageName != myPkg }
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    /** 应用列表适配器：图标 + 名称 + 包名。 */
    private inner class AppAdapter(private val items: List<AppItem>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView ?: LayoutInflater.from(this@AppListActivity)
                .inflate(R.layout.app_list_item, parent, false)
            val item = items[position]
            view.findViewById<ImageView>(R.id.ivAppIcon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.tvAppName).text = item.label
            view.findViewById<TextView>(R.id.tvAppPkg).text = item.packageName
            return view
        }
    }
}
