package com.akari.ppx.xp.hook.assist

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.akari.ppx.data.XPrefs
import com.akari.ppx.utils.CustomEmoticon
import com.akari.ppx.utils.EmoticonGroups
import com.akari.ppx.utils.callMethod
import com.akari.ppx.utils.callMethodAs
import com.akari.ppx.utils.callMethodOrNull
import com.akari.ppx.utils.callStaticMethod
import com.akari.ppx.utils.new
import com.akari.ppx.utils.check
import com.akari.ppx.utils.findClass
import com.akari.ppx.utils.getStaticObjectField
import com.akari.ppx.utils.getStaticObjectFieldAs
import com.akari.ppx.utils.hookAfterMethod
import com.akari.ppx.utils.hookBeforeMethod
import com.akari.ppx.utils.HookParam
import com.akari.ppx.utils.Log
import com.akari.ppx.xp.Init.cl
import com.akari.ppx.xp.hook.BaseHook
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicReference

/**
 * Custom emoticon groups layered on the host's emoji panel:
 * - an extra tab icon at the panel bottom bar opens the group browser,
 * - long-pressing a panel cell (favorite/meme/sticker tabs) offers collecting
 *   to the host's default emoticons or to a module group,
 * - long-pressing "save to emoticon" on a comment with several images offers a
 *   one-tap batch collect into both destinations.
 *
 * Insertion reuses the host's own path: the panel insert callback captured
 * from EmojiPanelManager.showEmojiPanel is invoked with an EmoticonModel that
 * EmojiService.collectEmoticon stored (it downloads and dedupes by uri).
 */
class CustomEmoticonHook : BaseHook {
    private val main = Handler(Looper.getMainLooper())

    private val panelViewRef = AtomicReference<WeakReference<View>?>(null)
    private val insertCallback = AtomicReference<WeakReference<Any>?>(null)

    override fun onHook() {
        XPrefs<Boolean>("enable_custom_emoticon").check(true) {
            setupToolbarEntry()
            setupPanelEntry()
        }
        XPrefs<Boolean>("batch_collect_emoticon").check(true) { setupBatchCollect() }
    }

    private fun emojiService(): Any? =
        "com.bytedance.news.common.service.manager.ServiceManager".findClass(cl)
            .callStaticMethod("getService", "com.sup.android.superb.i_emoji.IEmojiService".findClass(cl))

    private fun toast(context: Context?, message: String) {
        val target = context ?: return
        main.post { Toast.makeText(target, message, Toast.LENGTH_SHORT).show() }
    }

    // region panel entry + long-press

    private fun setupPanelEntry() {
        val managerClass = "com.sup.android.module.publish.emoji.e".findClass(cl)
        val showMethod = managerClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 5 && m.parameterTypes[1] == ViewGroup::class.java
                    && m.parameterTypes[2] == EditText::class.java
        }?.name ?: run { Log.i("CustomEmoticon panel manager method missing"); return }
        managerClass.hookAfterMethod(
            showMethod,
            java.lang.Boolean.TYPE,
            ViewGroup::class.java,
            EditText::class.java,
            Map::class.java,
            "kotlin.jvm.functions.Function1"
        ) { param ->
            insertCallback.set(WeakReference(param.args[4]))
            val container = param.args[1] as ViewGroup
            val panel = (0 until container.childCount)
                .map(container::getChildAt)
                .firstOrNull { it.javaClass.name == "com.sup.android.module.publish.emoji.d" }
            panel?.let {
                panelViewRef.set(WeakReference(it))
                main.post { wireCellLongPress(it) }
            }
        }
        // Pages render lazily per tab; rewire cells whenever the tab changes.
        val panelClass = "com.sup.android.module.publish.emoji.d".findClass(cl)
        val selectMethod = panelClass.declaredMethods.firstOrNull { m ->
            m.parameterTypes.size == 1 && m.parameterTypes[0] == Int::class.javaPrimitiveType
        }?.name
        selectMethod?.let { name ->
            panelClass.hookAfterMethod(name, Int::class.javaPrimitiveType) { param ->
                main.post { wireCellLongPress(param.thisObject as View) }
            }
        }
    }

    /** Adds a gradient "皮" button next to the assistant's 虎 anchor in the
     * comment toolbar; positioned against the GIF anchor like QuickActionsHook. */
    private fun setupToolbarEntry() {
        val callback = "com.sup.android.module.publish.view.NewInputCommentDialog\$tryPublish\$2".findClass(cl)
        val dialogClass = callback.declaredFields.first { Dialog::class.java.isAssignableFrom(it.type) }.type
        dialogClass.hookAfterMethod("onCreate", Bundle::class.java) { param ->
            val dialog = param.thisObject as Dialog
            dialog.window?.decorView?.post { runCatching { attachToolbarButton(dialog) }.onFailure(Log::e) }
        }
    }

    private fun attachToolbarButton(dialog: Dialog) {
        val root = dialog.window?.decorView as? FrameLayout ?: return
        if (root.findViewWithTag<View>("ppx_custom_emoticon") != null) return
        val ids = "com.sup.android.module.publish.R\$id".findClass(cl)
        val gif = root.findViewById<View>(ids.getStaticObjectFieldAs<Int>("iv_comment_gif")) ?: return
        val density = dialog.context.resources.displayMetrics.density
        val button = TextView(dialog.context).apply {
            tag = "ppx_custom_emoticon"
            text = "皮"
            textSize = 16f
            gravity = Gravity.CENTER
            contentDescription = "自定义表情"
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                colors = intArrayOf(0xFFED6559.toInt(), 0xFFFF9E58.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
            }
            elevation = dp(dialog.context, 3).toFloat()
            isClickable = true; isFocusable = true
            setOnClickListener { showGroupsDialog(dialog.context) }
        }
        root.addView(button, FrameLayout.LayoutParams(dp(dialog.context, 36), dp(dialog.context, 36)))
        root.viewTreeObserver.addOnGlobalLayoutListener {
            button.visibility = if (gif.isShown) View.VISIBLE else View.GONE
            val origin = IntArray(2); val anchor = IntArray(2)
            root.getLocationInWindow(origin); gif.getLocationInWindow(anchor)
            button.x = (anchor[0] - origin[0] + gif.width + dp(dialog.context, 12) + dp(dialog.context, 36) + dp(dialog.context, 8))
                .coerceAtMost(root.width - button.width - dp(dialog.context, 12)).toFloat()
            button.y = (anchor[1] - origin[1] + (gif.height - button.height) / 2).toFloat()
        }
    }

    private fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    /** Wires long-press save dialogs onto every favorite/meme/sticker grid cell. */
    private fun wireCellLongPress(panel: View) {
        val viewPager = findChildByClass(panel, "com.sup.android.module.publish.emoji.WrapContentViewPager") ?: return
        if (viewPager !is ViewGroup) return
        for (pageIndex in 0 until viewPager.childCount) {
            val page = viewPager.getChildAt(pageIndex)
            if (!page.javaClass.name.startsWith("com.sup.android.module.publish.emoji.view.")) continue
            runCatching {
                fun fieldUpTo(name: String) = generateSequence(page.javaClass as Class<*>?) { it.superclass }
                    .firstOrNull { it != null && runCatching { it.getDeclaredField(name) }.isSuccess }
                    ?.getDeclaredField(name)?.apply { isAccessible = true }
                val indexMap = fieldUpTo("c")?.get(page) as? Map<*, *> ?: return
                val position = fieldUpTo("d")?.get(page) as? Int ?: 0
                val drawableForIndex = fieldUpTo("h")?.get(page)
                indexMap.entries.forEach { entry ->
                    val index = (entry.key as? Number)?.toInt() ?: return@forEach
                    val cell = entry.value as? View ?: return@forEach
                    if (cell.tag == "ppx_long_press") return@forEach
                    cell.tag = "ppx_long_press"
                    cell.setOnLongClickListener {
                        val source = drawableForIndex?.callMethod("invoke", position, index)
                        if (source is String) showCellSaveDialog(cell.context, source)
                        true
                    }
                }
            }.onFailure(Log::e)
        }
    }

    private fun findChildByClass(root: View, className: String): View? {
        if (root.javaClass.name == className) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findChildByClass(root.getChildAt(index), className)?.let { return it }
            }
        }
        return null
    }

    // endregion

    // region per-cell save dialog

    private fun showCellSaveDialog(context: Context, localPath: String) {
        val service = emojiService() ?: return
        val models = emoticonModels(service)
        val model = models.firstOrNull { it.path == localPath }
        val items = mutableListOf("收藏到默认表情")
        if (!model?.uri.isNullOrBlank()) items += "加入自定义分组"
        AlertDialog.Builder(context)
            .setTitle("保存表情")
            .setItems(items.toTypedArray()) { dialog, which ->
                when (items[which]) {
                    "收藏到默认表情" -> {
                        if (model != null) toast(context, "已在默认表情中")
                        else collectLocal(service, context, localPath)
                    }
                    "加入自定义分组" -> showGroupPicker(context) { group ->
                        val uri = model?.uri.orEmpty()
                        if (uri.isBlank()) {
                            toast(context, "该表情没有来源链接，暂不支持加入分组")
                            return@showGroupPicker
                        }
                        val w = model?.width ?: decodeBounds(localPath).first
                        val h = model?.height ?: decodeBounds(localPath).second
                        val added = EmoticonGroups.addEmoji(
                            group,
                            CustomEmoticon(uri, w, h, model?.gif ?: false)
                        )
                        toast(context, if (added) "已加入分组「$group」" else "分组中已存在")
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // mirrors EmoticonModel field access through one holder
    private class EmoticonRef(val path: String, val uri: String?, val width: Int, val height: Int, val gif: Boolean)

    private fun emoticonModels(service: Any): List<EmoticonRef> {
        val out = mutableListOf<EmoticonRef>()
        listOf(2, 4).forEach { type ->
            runCatching {
                (service.callMethod("getEmojiListByType", type) as? List<*>)?.forEach { item ->
                    val path = item?.callMethodOrNull("getLocalPath") as? String ?: return@forEach
                    out += EmoticonRef(
                        path = path,
                        uri = item.callMethodOrNull("getUri") as? String,
                        width = (item.callMethodOrNull("getWidth") as? Number)?.toInt() ?: 0,
                        height = (item.callMethodOrNull("getHeight") as? Number)?.toInt() ?: 0,
                        gif = (item.callMethodOrNull("getIsGif") as? Boolean)
                            ?: (item.callMethodOrNull("isGif") as? Boolean) ?: false
                    )
                }
            }.onFailure(Log::e)
        }
        return out
    }

    private fun decodeBounds(path: String): Pair<Int, Int> = runCatching {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, options)
        options.outWidth to options.outHeight
    }.getOrDefault(0 to 0)

    private fun singleCallback(context: Context, onResult: (Int) -> Unit): Any {
        val iface = "com.sup.android.superb.i_emoji.IEmojiService\$SingleCallBack".findClass(cl)
        return java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(iface)) { _, method, args ->
            if (method.name == "onFinish") {
                val result = (args?.getOrNull(0) as? Number)?.toInt() ?: 0
                main.post { onResult(result) }
            }
            null
        }
    }

    private fun collectLocal(service: Any, context: Context, localPath: String) {
        val (w, h) = decodeBounds(localPath)
        val gif = localPath.lowercase().endsWith(".gif")
        runCatching {
            service.callMethod(
                "collectLocalEmoticon", localPath, w, h, gif,
                singleCallback(context) { result ->
                    toast(context, if (result == 1) "已收藏到默认表情" else "收藏失败（代码 $result）")
                }
            )
        }.onFailure { Log.e(it); toast(context, "收藏失败") }
    }

    // endregion

    // region group browser

    private fun showGroupsDialog(context: Context) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow((context as? Activity)?.window?.decorView?.windowToken, 0)
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val accent = 0xFFED6559.toInt()

        var chips: LinearLayout? = null
        var grid: GridLayout? = null
        var selected: String? = EmoticonGroups.load().firstOrNull()?.name

        fun renderGrid(groupName: String?) {
            val grid = grid ?: return
            val group = groupName ?: run {
                grid.removeAllViews()
                grid.addView(TextView(context).apply {
                    text = "还没有分组，点上方「＋ 添加分组」创建"
                    setTextColor(0xFF999999.toInt()); gravity = Gravity.CENTER
                    setPadding(0, dp(48), 0, dp(48))
                }, GridLayout.LayoutParams().apply { width = GridLayout.LayoutParams.MATCH_PARENT })
                return
            }
            grid.removeAllViews()
            val emojis = EmoticonGroups.load().firstOrNull { it.name == group }?.emojis ?: return
            emojis.forEach { emoji ->
                val cell = FrameLayout(context).apply { setPadding(dp(5), dp(5), dp(5), dp(5)) }
                val view = "com.facebook.drawee.view.SimpleDraweeView".findClass(cl).new(context) as View
                view.layoutParams = FrameLayout.LayoutParams(dp(80), dp(80))
                view.background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x0D000000); cornerRadius = dp(12).toFloat()
                }
                runCatching {
                    "com.bytedance.ies.xelement.video.base.utils.FrescoHelper".findClass(cl)
                        .callStaticMethod("bindImage", view, emoji.uri, 0, 0, Bitmap.Config.RGB_565)
                }.onFailure {
                    view.callMethod("setImageURI", Uri.parse(emoji.uri))
                }
                view.setOnClickListener { insertEmoticon(context, emoji) }
                view.setOnLongClickListener {
                    AlertDialog.Builder(context)
                        .setTitle("从分组移除")
                        .setMessage("将该表情移出「$group」？")
                        .setPositiveButton("移除") { d, _ ->
                            EmoticonGroups.removeEmoji(group, emoji.uri)
                            renderGrid(group); d.dismiss()
                        }
                        .setNegativeButton("取消", null).show()
                    true
                }
                cell.addView(view)
                grid.addView(cell)
            }
            if (emojis.isEmpty()) {
                grid.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    setPadding(0, dp(48), 0, dp(48))
                    addView(TextView(context).apply {
                        text = "皮"; textSize = 40f; gravity = Gravity.CENTER
                        setTextColor(0x22000000.toInt())
                    })
                    addView(TextView(context).apply {
                        text = "分组还是空的，长按表情面板里的表情加入"
                        setTextColor(0xFF999999.toInt()); setPadding(0, dp(12), 0, 0)
                    })
                }, GridLayout.LayoutParams().apply { width = GridLayout.LayoutParams.MATCH_PARENT })
            }
        }

        fun renderChips(active: String?) {
            val row = chips ?: return
            row.removeAllViews()
            fun chip(label: String, filled: Boolean, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
                row.addView(TextView(context).apply {
                    text = label
                    setPadding(dp(18), dp(9), dp(18), dp(9))
                    gravity = Gravity.CENTER
                    if (filled) {
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(20).toFloat(); setColor(accent)
                        }
                    } else {
                        setTextColor(0xFF444444.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(20).toFloat(); setColor(0x0F000000)
                        }
                    }
                    setOnClickListener { onClick() }
                    onLong?.let { listener -> setOnLongClickListener { listener(); true } }
                })
            }
            EmoticonGroups.load().forEach { group ->
                val activeGroup = group.name == active
                chip("${group.name} ${group.emojis.size}", activeGroup, {
                    selected = group.name; renderChips(selected); renderGrid(selected)
                }, {
                    AlertDialog.Builder(context)
                        .setTitle("分组：${group.name}")
                        .setItems(arrayOf("重命名", "删除分组")) { d, which ->
                            when (which) {
                                0 -> renameDialog(context, group.name) { newName ->
                                    EmoticonGroups.renameGroup(group.name, newName)
                                    selected = newName; renderChips(selected); renderGrid(selected)
                                }
                                1 -> {
                                    EmoticonGroups.deleteGroup(group.name)
                                    selected = EmoticonGroups.load().firstOrNull()?.name
                                    renderChips(selected); renderGrid(selected)
                                }
                            }
                            d.dismiss()
                        }
                        .setNegativeButton("取消", null).show()
                })
            }
            chip("＋ 添加分组", true, {
                renameDialog(context, null) { name ->
                    EmoticonGroups.createGroup(name)
                    selected = name; renderChips(selected); renderGrid(selected)
                }
            })
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadii = floatArrayOf(dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), 0f, 0f, 0f, 0f)
                setColor(Color.WHITE)
            }
            setPadding(dp(20), dp(10), dp(20), dp(24))
        }
        root.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL }
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(2).toFloat(); setColor(0x33000000)
            }
        })
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(4))
            addView(TextView(context).apply {
                text = "自定义表情"; textSize = 18f
                setTextColor(0xFF222222.toInt())
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
        }
        titleRow.addView(TextView(context).apply {
            text = "导入"; textSize = 14f
            setTextColor(accent); setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { importDialog(context) { renderChips(selected); renderGrid(selected) } }
        })
        titleRow.addView(TextView(context).apply {
            text = "导出"; textSize = 14f
            setTextColor(accent); setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("皮友助手自定义表情", EmoticonGroups.export()))
                Toast.makeText(context, "分组 JSON 已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        })
        root.addView(titleRow)
        chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, dp(4))
        }
        root.addView(HorizontalScrollView(context).apply {
            addView(chips); scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        })
        grid = GridLayout(context).apply { columnCount = 4 }
        root.addView(ScrollView(context).apply {
            addView(grid)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(400)
            ).apply { topMargin = dp(6) }
        })

        val dialog = Dialog(context)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, dp(620))
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
        renderChips(selected)
        renderGrid(selected)
    }

    /** Centered rounded-card dialog matching the group browser style. */
    private fun cardDialog(context: Context, title: String, content: View, positive: String, onPositive: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val dialog = Dialog(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(Color.WHITE)
            }
        }
        root.addView(TextView(context).apply {
            text = title; textSize = 16f
            setTextColor(0xFF222222.toInt())
        })
        root.addView(content, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, dp(14), 0, dp(6)) })
        root.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
            fun action(label: String, filled: Boolean, block: () -> Unit) {
                addView(TextView(context).apply {
                    text = label
                    setPadding(dp(18), dp(8), dp(18), dp(8))
                    gravity = Gravity.CENTER
                    if (filled) {
                        setTextColor(Color.WHITE)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat(); setColor(0xFFED6559.toInt())
                        }
                    } else {
                        setTextColor(0xFF666666.toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = dp(18).toFloat(); setColor(0x14000000)
                        }
                    }
                    setOnClickListener { block() }
                })
            }
            action("取消", false) { dialog.dismiss() }
            action(positive, true) {
                dialog.dismiss()
                onPositive()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        dialog.setContentView(FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            addView(root, FrameLayout.LayoutParams(
                context.resources.displayMetrics.widthPixels - dp(48),
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
            ))
        })
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        dialog.show()
    }

    private fun renameDialog(context: Context, current: String?, onDone: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val input = EditText(context).apply {
            setText(current); hint = "输入分组名称"
            setTextColor(0xFF222222.toInt()); setHintTextColor(0xFF999999.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x0F000000)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        cardDialog(context, if (current == null) "新建分组" else "重命名分组", input, "确定") {
            val name = input.text.toString().trim()
            if (name.isNotEmpty()) onDone(name)
        }
    }

    private fun importDialog(context: Context, onDone: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + 0.5f).toInt()
        val input = EditText(context).apply {
            hint = "粘贴导出的分组 JSON"
            setTextColor(0xFF222222.toInt()); setHintTextColor(0xFF999999.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(12).toFloat(); setColor(0x0F000000)
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minHeight = dp(120); gravity = Gravity.TOP
        }
        cardDialog(context, "导入自定义表情", input, "导入") {
            val added = EmoticonGroups.import(input.text.toString())
            Toast.makeText(context, if (added >= 0) "导入完成，新增 $added 个表情" else "JSON 解析失败", Toast.LENGTH_SHORT).show()
            if (added >= 0) onDone()
        }
    }

    private fun showGroupPicker(context: Context, onPick: (String) -> Unit) {
        val groups = EmoticonGroups.load()
        val names = groups.map { it.name }.toMutableList().apply { add("＋ 新建分组") }
        AlertDialog.Builder(context)
            .setTitle("加入自定义分组")
            .setItems(names.toTypedArray()) { d, which ->
                if (which == names.lastIndex) {
                    renameDialog(context, null) { name ->
                        EmoticonGroups.createGroup(name)
                        onPick(name)
                    }
                } else onPick(names[which])
                d.dismiss()
            }
            .setNegativeButton("取消", null).show()
    }

    // endregion

    // region insertion

    private fun insertEmoticon(context: Context, emoji: CustomEmoticon) {
        val service = emojiService()
        if (service == null) { toast(context, "表情服务不可用"); return }
        val collected = findCollected(service, emoji.uri)
        if (collected != null) {
            invokeInsert(context, collected)
            return
        }
        val imageModel = "com.sup.android.base.model.ImageModel".findClass(cl).new()
        imageModel.callMethod("setUri", emoji.uri)
        imageModel.callMethod("setWidth", emoji.w)
        imageModel.callMethod("setHeight", emoji.h)
        imageModel.callMethod("setGif", emoji.gif)
        imageModel.callMethod(
            "setUrlList",
            arrayListOf("com.sup.android.base.model.ImageUrlModel".findClass(cl).new(emoji.uri))
        )
        val callback = singleCallback(context) { result ->
            val model = findCollected(service, emoji.uri)
            if (model != null) invokeInsert(context, model)
            else toast(context, "表情下载失败（代码 $result）")
        }
        runCatching {
            service.callMethod("collectEmoticon", imageModel, 0L, 0L, 0L, callback)
        }.onFailure { Log.e(it); toast(context, "表情服务调用失败") }
    }

    private fun findCollected(service: Any, uri: String): Any? =
        runCatching {
            (service.callMethod("getEmojiListByType", 2) as? List<*>)?.firstOrNull { item ->
                item?.callMethodOrNull("getUri") == uri
            }
        }.getOrNull()

    private fun invokeInsert(context: Context, model: Any) {
        val callback = insertCallback.get()?.get()
        if (callback == null) {
            toast(context, "请先打开评论输入框的表情面板")
            return
        }
        runCatching { callback.callMethod("invoke", model) }
            .onFailure { Log.e(it); toast(context, "插入失败") }
    }

    // endregion

    // region comment batch collect

    private val gridComments = java.util.WeakHashMap<View, Any>()

    private fun setupBatchCollect() {
        // Comment list and detail pages save through two different helpers.
        runCatching {
            val helperClass = "com.sup.android.m_comment.util.helper.c".findClass(cl)
            val saveMethod = helperClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 3 && m.parameterTypes[0] == Activity::class.java
            }?.name ?: error("CommentHelper save method missing")
            helperClass.hookBeforeMethod(
                saveMethod,
                Activity::class.java,
                "com.sup.android.mi.feed.repo.bean.cell.AbsFeedCell",
                Int::class.javaPrimitiveType
            ) { param -> interceptBatch(param) }
        }.onFailure(Log::e)
        runCatching {
            "com.sup.android.detail.util.h".findClass(cl).declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 3 && m.parameterTypes[0] == Activity::class.java
                        && m.parameterTypes[1].name.endsWith("AbsFeedCell")
            }?.let { method ->
                method.hookBeforeMethod { param -> interceptBatch(param) }
            }
        }.onFailure(Log::e)
        // Long-pressing a comment image opens the batch dialog directly,
        // bypassing the host's report/add-to-emoticon bubble.
        runCatching {
            val gridClass = "com.sup.android.uikit.imagegrid.MultiImageGrid".findClass(cl)
            val holderClass = "com.sup.android.m_comment.docker.holder.e".findClass(cl)
            val bindMethod = holderClass.declaredMethods.firstOrNull { m ->
                m.parameterTypes.size == 2 && m.parameterTypes[0].name.endsWith("comment.Comment")
            } ?: error("grid bind method missing")
            val gridField = holderClass.declaredFields.firstOrNull {
                it.type.name.endsWith("MultiImageGrid")
            }?.apply { isAccessible = true } ?: error("grid field missing")
            Log.i("CustomEmoticon grid bind method=${bindMethod.name}")
            bindMethod.hookAfterMethod { param ->
                val grid = gridField.get(param.thisObject) as? View ?: return@hookAfterMethod
                (param.args[0] as? Any)?.let { gridComments[grid] = it }
            }
            gridClass.declaredMethods.firstOrNull { it.name == "setGridViewLongClick" }?.let { setLc ->
                Log.i("CustomEmoticon grid wrapper armed")
                setLc.hookBeforeMethod { param ->
                    if (!XPrefs<Boolean>("batch_collect_emoticon")) return@hookBeforeMethod
                    val grid = param.thisObject as View
                    val host = param.args[0]
                    val iface = "com.sup.android.uikit.imagegrid.MultiImageGrid\$b".findClass(cl)
                    param.args[0] = java.lang.reflect.Proxy.newProxyInstance(cl, arrayOf(iface)) { _, method, args ->
                        if (method.name == "onLongClick") {
                            val view = args?.getOrNull(1) as? View
                            val context = view?.context
                            val comment = gridComments[grid]
                            Log.i("CustomEmoticon grid long-click context=${context?.javaClass?.name} isActivity=${context is Activity} comment=${comment != null}")
                            if (context is Activity && comment != null) {
                                val images = runCatching {
                                    comment.callMethodAs<List<Any?>>("getImages")
                                }.getOrNull().orEmpty()
                                Log.i("CustomEmoticon images=${images.size}")
                                if (images.isNotEmpty()) {
                                    main.post {
                                        runCatching { showBatchDialog(context, images) }.onFailure(Log::e)
                                        Log.i("CustomEmoticon batch dialog posted")
                                    }
                                    return@newProxyInstance true
                                }
                            }
                        }
                        host?.callMethod(method.name, *(args ?: emptyArray<Any?>()))
                    }
                }
            }
        }.onFailure(Log::e)
    }

    private fun showBatchDialog(context: Activity, images: List<Any?>) {
        AlertDialog.Builder(context)
            .setTitle("一键收藏表情（${images.size} 张）")
            .setMessage("将该评论里的全部表情添加到哪里？")
            .setPositiveButton("添加到官方表情") { d, _ ->
                val service = emojiService() ?: return@setPositiveButton
                val success = java.util.concurrent.atomic.AtomicInteger()
                val total = images.filterNotNull().size
                val callback = singleCallback(context) { result ->
                    if (result == 1 || result == 8) {
                        val done = success.incrementAndGet()
                        if (done == total) toast(context, "已收藏 $done 张表情")
                    }
                }
                images.filterNotNull().forEach { image ->
                    runCatching { service.callMethod("collectEmoticon", image, 0L, 0L, 0L, callback) }
                        .onFailure { Log.e(it) }
                }
                d.dismiss()
            }
            .setNegativeButton("添加到自定义分组") { d, _ ->
                showGroupPicker(context) { group ->
                    var added = 0
                    images.filterNotNull().forEach { image ->
                        val uri = ((image.callMethodOrNull("getUri") as? String)
                            ?: ((image.callMethodOrNull("getUrlList") as? List<*>)?.firstOrNull()
                                ?.callMethodOrNull("getUrl") as? String)).orEmpty()
                        if (uri.isBlank()) return@forEach
                        val w = (image.callMethodOrNull("getWidth") as? Number)?.toInt() ?: 0
                        val h = (image.callMethodOrNull("getHeight") as? Number)?.toInt() ?: 0
                        val gif = (image.callMethodOrNull("isGif") as? Boolean) ?: false
                        if (EmoticonGroups.addEmoji(group, CustomEmoticon(uri, w, h, gif))) added++
                    }
                    toast(context, "已加入分组「$group」$added 张")
                }
                d.dismiss()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun interceptBatch(param: com.akari.ppx.utils.HookParam) {
        val context = param.args[0] as Activity
        val cell = param.args[1] ?: return
        val util = "com.sup.android.mi.feed.repo.utils.b\$a".findClass(cl)
            .getStaticObjectField("a") ?: return
        val images = runCatching { util.callMethodAs<List<Any?>>("ad", cell) }.getOrNull().orEmpty()
        if (images.isEmpty()) return
        param.result = null
        showBatchDialog(context, images)
    }

    // endregion
}
