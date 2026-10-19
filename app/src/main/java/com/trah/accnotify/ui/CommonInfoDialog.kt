package com.trah.accnotify.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.color.MaterialColors
import com.trah.accnotify.R
import com.trah.accnotify.crypto.KeyManager
import com.trah.accnotify.crypto.SenderEncryption

object CommonInfoDialog {
    fun show(context: Context, keys: KeyManager, onCurrentServerKeyChanged: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_clean, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = "通用信息设置"
        view.findViewById<TextView>(R.id.dialogMessage).text =
            "修改公用秘钥并保存后，复用它的服务器会自动更新。公用 ID 的修改不会更换已保存的服务器 ID。"
        val density = context.resources.displayMetrics.density
        val touchSize = context.resources.getDimensionPixelSize(R.dimen.action_touch_size)
        val textColor = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        view.findViewById<FrameLayout>(R.id.dialogCustomContainer).apply {
            visibility = View.VISIBLE
            addView(content)
        }

        fun addField(label: String, value: String, editable: Boolean, generate: () -> String): EditText {
            val followingField = content.childCount > 0
            content.addView(TextView(context).apply {
                text = label
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = context.resources.getDimensionPixelSize(R.dimen.spacing_small)
                    if (followingField) topMargin = context.resources.getDimensionPixelSize(R.dimen.spacing_small)
                }
            })
            val row = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            val input = EditText(context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setSingleLine(true)
                setText(value)
                transformationMethod = null
                if (!editable) keyListener = null
                contentDescription = label
                setTextColor(textColor)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
                setBackgroundResource(R.drawable.bg_clean_input)
                val padding = resources.getDimensionPixelSize(R.dimen.spacing_control)
                setPadding(padding, padding, padding, padding)
                minHeight = touchSize
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(input)
            fun iconButton(icon: Int, description: String, onClick: () -> Unit) {
                row.addView(KeyDialogControls.icon(context, view, icon, description, onClick))
            }
            iconButton(R.drawable.ic_fluent_copy, "复制$label") {
                try {
                    val copied = if (editable) SenderEncryption.normalizeKey(input.text.toString())
                        else input.text.toString()
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText(label, copied)
                    clip.description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "$label 已复制", Toast.LENGTH_SHORT).show()
                } catch (_: IllegalArgumentException) {
                    input.error = "请输入 64 位十六进制密钥"
                } catch (_: Exception) {
                    Toast.makeText(context, "复制失败，请重试", Toast.LENGTH_SHORT).show()
                }
            }
            iconButton(R.drawable.ic_fluent_refresh, "重置$label") {
                KeyDialogControls.confirmReset(context, "重置$label",
                    if (editable) "生成新的$label，保存后复用它的服务器会自动更新。请同步更新发送脚本。"
                    else "生成新的$label，保存后生效。已保存的服务器 ID 不会自动更换。") {
                    input.setText(generate())
                    input.error = null
                }
            }
            content.addView(row)
            return input
        }

        val deviceId = addField("公用 ID", keys.getCommonDeviceId(), editable = false) { keys.generateDeviceId() }
        val encryptionKey = addField("公用秘钥", keys.getCommonSenderEncryptionKey(), editable = true) {
            SenderEncryption.generateKey()
        }
        val dialog = AlertDialog.Builder(context).setView(view).create()
        val cancel = view.findViewById<TextView>(R.id.btnNegative)
        val save = view.findViewById<TextView>(R.id.btnPositive)
        KeyDialogControls.textActions(context, view)
        cancel.text = "取消"
        cancel.setOnClickListener { dialog.dismiss() }
        save.text = "保存"
        save.setOnClickListener {
            try {
                val currentServerKeyChanged = keys.setCommonInfo(deviceId.text.toString(), encryptionKey.text.toString())
                dialog.dismiss()
                Toast.makeText(context, "通用信息已保存", Toast.LENGTH_SHORT).show()
                if (currentServerKeyChanged) onCurrentServerKeyChanged()
            } catch (_: IllegalArgumentException) {
                encryptionKey.error = "请输入 64 位十六进制密钥"
            } catch (_: Exception) {
                Toast.makeText(context, "保存失败，请重试", Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
        KeyDialogControls.compactWindow(context, dialog.window)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
