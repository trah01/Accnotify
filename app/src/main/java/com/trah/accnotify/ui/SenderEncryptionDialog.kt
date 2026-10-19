package com.trah.accnotify.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.trah.accnotify.R
import com.trah.accnotify.crypto.KeyManager
import com.trah.accnotify.crypto.SenderEncryption
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.MaterialColors

object SenderEncryptionDialog {
    fun show(context: Context, keys: KeyManager, onSaved: () -> Unit) {
        val server = keys.serverUrl
        val title = "当前服务器密钥"
        var reuseCommonKey = false
        fun saveKey(value: String) {
            keys.setSenderEncryptionKey(server, value, reuseCommonKey)
        }
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_clean, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text =
            "服务器：$server\n发送脚本须使用同一密钥；更换或清除后，需要及时更新，否则将无法解密。"
        val container = view.findViewById<FrameLayout>(R.id.dialogCustomContainer)
        container.visibility = View.VISIBLE
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(context).apply {
            hint = "64 位十六进制密钥，留空关闭"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setSingleLine(true)
            setText(keys.getSenderEncryptionKey(server) ?: "")
            transformationMethod = null
            setTextColor(MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
            background = ContextCompat.getDrawable(context, R.drawable.bg_clean_input)
            val padding = resources.getDimensionPixelSize(R.dimen.spacing_control)
            setPadding(padding, padding, padding, padding)
            minHeight = resources.getDimensionPixelSize(R.dimen.action_touch_size)
            contentDescription = title
        }
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        input.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        inputRow.addView(input)
        inputRow.addView(KeyDialogControls.icon(context, view, R.drawable.ic_fluent_copy, "复制密钥") {
            try {
                val key = if (reuseCommonKey) keys.getCommonSenderEncryptionKey()
                    else SenderEncryption.normalizeKey(input.text.toString())
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText(title, key)
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "密钥已复制", Toast.LENGTH_SHORT).show()
            } catch (_: IllegalArgumentException) { input.error = "请输入或生成有效密钥" }
            catch (_: Exception) { input.error = "复制失败，请重试" }
        })
        val reset = KeyDialogControls.icon(context, view, R.drawable.ic_fluent_refresh, "重置密钥") {
            KeyDialogControls.confirmReset(context, "重置当前服务器密钥",
                "生成新的密钥，保存后生效。保存后请同步更新发送脚本，否则无法解密。") {
                input.setText(SenderEncryption.generateKey())
                input.error = null
            }
        }
        inputRow.addView(reset)
        content.addView(inputRow)
        container.addView(content)
        val dialog = AlertDialog.Builder(context).setView(view).create()
        KeyDialogControls.textActions(context, view)
        view.findViewById<TextView>(R.id.btnNegative).apply {
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }
        run {
            val commonKey = keys.getCommonSenderEncryptionKey()
            var privateDraft = input.text.toString()
            val reuse = MaterialCheckBox(context).apply {
                text = "复用公用秘钥"
                setOnCheckedChangeListener { _, checked ->
                    reuseCommonKey = checked
                    if (checked) {
                        privateDraft = input.text.toString()
                        input.setText(commonKey)
                        input.clearFocus()
                    } else {
                        input.setText(privateDraft)
                    }
                    input.error = null
                    input.isEnabled = !checked
                    input.backgroundTintList = if (checked) android.content.res.ColorStateList.valueOf(
                        MaterialColors.getColor(input, com.google.android.material.R.attr.colorSurfaceVariant)) else null
                    input.alpha = if (checked) 0.6f else 1f
                    reset.isEnabled = !checked
                    reset.alpha = if (checked) 0.38f else 1f
                }
            }
            content.addView(reuse)
            reuse.isChecked = keys.isUsingCommonSenderKey(server)
        }
        view.findViewById<TextView>(R.id.btnPositive).apply {
            text = "保存"
            setOnClickListener {
                try {
                    saveKey(input.text.toString())
                    dialog.dismiss()
                    Toast.makeText(context, if (input.text.isBlank()) "已关闭发送端加密解密" else "密钥已保存", Toast.LENGTH_SHORT).show()
                    onSaved()
                } catch (_: IllegalArgumentException) {
                    input.error = "请输入 64 位十六进制密钥，或留空关闭"
                }
                catch (_: Exception) { input.error = "保存失败，请重试" }
            }
        }
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.show()
        KeyDialogControls.compactWindow(context, dialog.window)
    }
}
