package com.trah.accnotify.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.widget.ListPopupWindow
import com.google.android.material.color.MaterialColors
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.trah.accnotify.R
import com.trah.accnotify.crypto.KeyManager

object DeviceIdSelector {
    fun show(context: Context, anchor: View, keys: KeyManager, onChanged: (Boolean) -> Unit) {
        val server = keys.serverUrl
        val usingCommon = keys.isUsingCommonDeviceId(server)
        val density = context.resources.displayMetrics.density
        val surface = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurface)
        val outline = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOutlineVariant)
        val accent = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorPrimary)
        val textColor = MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorOnSurface)
        val popup = ListPopupWindow(context).apply {
            anchorView = anchor
            isModal = true
            width = minOf((280 * density).toInt(), context.resources.displayMetrics.widthPixels - (32 * density).toInt())
            setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED)
            setBackgroundDrawable(MaterialShapeDrawable(ShapeAppearanceModel.builder()
                .setAllCornerSizes(12 * density).build()).apply {
                fillColor = ColorStateList.valueOf(surface)
                setStroke(density, outline)
            })
        }
        fun select(common: Boolean, regenerate: Boolean = false) {
            try {
                val changed = keys.selectDeviceId(server, common, regenerate)
                onChanged(changed)
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "设备 ID 保存失败，请重试", Toast.LENGTH_LONG).show()
            }
        }
        popup.setAdapter(object : BaseAdapter() {
            override fun getCount() = 2
            override fun getItem(position: Int) = position == 1
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val common = position == 1
                return LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val option = MaterialRadioButton(context).apply {
                        text = if (common) "公用 ID" else "私有随机 ID"
                        isChecked = usingCommon == common
                        setTextColor(if (isChecked) accent else textColor)
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                        minHeight = context.resources.getDimensionPixelSize(R.dimen.action_touch_size)
                        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
                        isClickable = false
                        isFocusable = false
                        setUseMaterialThemeColors(true)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    addView(option)
                    if (!common && !usingCommon) {
                        addView(KeyDialogControls.icon(context, anchor, R.drawable.ic_fluent_refresh,
                            "重新生成私有 ID") {
                            popup.dismiss()
                            KeyDialogControls.confirmReset(context, "重新生成私有 ID",
                                "确认后生成新的私有 ID。请重新同步连接，并更新发送脚本中的推送地址。") {
                                select(common = false, regenerate = true)
                            }
                        }.apply { isFocusable = false })
                    }
                }
            }
        })
        popup.setOnItemClickListener { _, _, position, _ ->
            popup.dismiss()
            select(common = position == 1)
        }
        popup.show()
        popup.setSelection(if (usingCommon) 1 else 0)
    }
}
