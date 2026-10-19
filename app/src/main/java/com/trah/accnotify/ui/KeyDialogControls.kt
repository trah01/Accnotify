package com.trah.accnotify.ui

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import com.trah.accnotify.R

internal object KeyDialogControls {
    fun compactWindow(context: Context, window: android.view.Window?) {
        val metrics = context.resources.displayMetrics
        val margin = context.resources.getDimensionPixelSize(R.dimen.spacing_page)
        window?.setLayout(minOf(metrics.widthPixels - margin * 2, (480 * metrics.density).toInt()),
            ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    fun confirmReset(context: Context, title: String, message: String, onConfirm: () -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_clean, null)
        view.findViewById<TextView>(R.id.dialogTitle).text = title
        view.findViewById<TextView>(R.id.dialogMessage).text = message
        textActions(context, view)
        val dialog = AlertDialog.Builder(context).setView(view).create()
        view.findViewById<TextView>(R.id.btnNegative).apply {
            text = "取消"
            setOnClickListener { dialog.dismiss() }
        }
        view.findViewById<TextView>(R.id.btnPositive).apply {
            text = "重置"
            setOnClickListener {
                onConfirm()
                dialog.dismiss()
            }
        }
        dialog.show()
        compactWindow(context, dialog.window)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    fun icon(context: Context, view: View, drawable: Int, label: String, onClick: () -> Unit) =
        ImageButton(context).apply {
            val size = context.resources.getDimensionPixelSize(R.dimen.action_touch_size)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setImageResource(drawable)
            contentDescription = label
            imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary))
            val padding = context.resources.getDimensionPixelSize(R.dimen.action_icon_padding)
            setPadding(padding, padding, padding, padding)
            val attr = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, attr, true)
            setBackgroundResource(attr.resourceId)
            setOnClickListener { onClick() }
        }

    fun textActions(context: Context, view: View) {
        val padding = context.resources.getDimensionPixelSize(R.dimen.spacing_card)
        val gap = context.resources.getDimensionPixelSize(R.dimen.spacing_small)
        val title = view.findViewById<TextView>(R.id.dialogTitle)
        (title.parent as View).setPadding(padding, padding, padding, gap)
        (title.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = gap
        (view.findViewById<View>(R.id.dialogCustomContainer).layoutParams as ViewGroup.MarginLayoutParams)
            .topMargin = gap
        val actions = view.findViewById<View>(R.id.btnPositive).parent as View
        (actions.layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0
        val color = MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimary)
        val size = context.resources.getDimensionPixelSize(R.dimen.action_touch_size)
        val attr = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, attr, true)
        for (id in listOf(R.id.btnNegative, R.id.btnPositive)) {
            view.findViewById<TextView>(id).apply {
                setTextColor(color)
                visibility = View.VISIBLE
                isFocusable = true
                minHeight = size
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                setBackgroundResource(attr.resourceId)
            }
        }
    }
}
