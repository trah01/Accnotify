package com.trah.accnotify.ui

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.trah.accnotify.R

/** Shared controls for notification settings and device dialogs. */
internal object NotificationUi {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun text(context: Context, label: String, secondary: Boolean = false) = TextView(context).apply {
        text = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
        setTextColor(MaterialColors.getColor(this, if (secondary)
            com.google.android.material.R.attr.colorOnSurfaceVariant else com.google.android.material.R.attr.colorOnSurface))
    }

    fun action(context: Context, label: String, primary: Boolean = false) = text(context, label).apply {
        gravity = Gravity.CENTER
        minHeight = context.resources.getDimensionPixelSize(R.dimen.control_height)
        isClickable = true
        isFocusable = true
        val padding = context.resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
        val verticalPadding = context.resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
        setPadding(padding, verticalPadding, padding, verticalPadding)
        if (primary) {
            setBackgroundResource(R.drawable.bg_clean_button_primary)
            setTextColor(context.getColorStateList(R.color.action_button_text))
        } else {
            val attr = android.util.TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
            setBackgroundResource(attr.resourceId)
            val color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 97), color)))
        }
    }

    fun input(context: Context, label: String) = EditText(context).apply {
        hint = label
        contentDescription = label
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(com.trah.accnotify.R.dimen.text_body))
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        setHintTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setBackgroundResource(R.drawable.bg_clean_input)
        val padding = context.resources.getDimensionPixelSize(R.dimen.control_padding_horizontal)
        val verticalPadding = context.resources.getDimensionPixelSize(R.dimen.control_padding_vertical)
        setPadding(padding, verticalPadding, padding, verticalPadding)
        minHeight = context.resources.getDimensionPixelSize(R.dimen.control_height)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = context.resources.getDimensionPixelSize(R.dimen.spacing_small) }
    }

    fun dialog(context: Context, title: String, content: View? = null, message: String? = null,
        positive: String? = null, onPositive: (() -> Unit)? = null,
        negative: String = "关闭"): AlertDialog {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_clean, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).apply {
            text = message
            visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        view.findViewById<FrameLayout>(R.id.dialogCustomContainer).apply {
            visibility = if (content == null) View.GONE else View.VISIBLE
            if (content != null) addView(content)
        }
        KeyDialogControls.textActions(context, view)
        val dialog = AlertDialog.Builder(context).setView(view).create()
        view.findViewById<TextView>(R.id.btnNegative).apply {
            text = negative
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<TextView>(R.id.btnPositive).apply {
            visibility = if (positive == null) View.GONE else View.VISIBLE
            text = positive
            setOnClickListener { dialog.dismiss(); onPositive?.invoke() }
        }
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            KeyDialogControls.compactWindow(context, dialog.window)
            if (content is android.widget.ScrollView) {
                val maxHeight = (context.resources.displayMetrics.heightPixels * 0.55f).toInt()
                if (content.measuredHeight > maxHeight) {
                    content.layoutParams = content.layoutParams.apply { height = maxHeight }
                }
            }
        }
        return dialog
    }
}
