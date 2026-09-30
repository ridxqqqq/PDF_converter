package com.pdftool.app.ui

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.TextView
import com.pdftool.app.R
import com.pdftool.app.model.ConversionType

/**
 * Framework-only grid adapter (no AndroidX / view binding). Each cell is a
 * rounded "card" built in code: an emoji glyph + the feature title.
 */
class FeatureGridAdapter(
    private val items: List<ConversionType>,
    private val onClick: (ConversionType) -> Unit
) : BaseAdapter() {

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val ctx = parent.context
        val type = items[position]
        val dark = (ctx.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val cardColor = ctx.resources.getColor(if (dark) R.color.card_dark else R.color.card_light)
        val strokeColor = ctx.resources.getColor(
            if (dark) R.color.card_stroke_dark else R.color.card_stroke_light
        )
        val textColor = ctx.resources.getColor(
            if (dark) R.color.text_primary_dark else R.color.text_primary_light
        )

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(16, 22, 16, 22)
            val bg = GradientDrawable().apply {
                setColor(cardColor)
                cornerRadius = dp(ctx, 14f)
                setStroke(1, strokeColor)
            }
            background = bg
            isClickable = true
            isFocusable = true
        }

        val emoji = TextView(ctx).apply {
            text = emojiFor(type)
            textSize = 30f
            gravity = Gravity.CENTER
        }
        val label = TextView(ctx).apply {
            text = type.title
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            setPadding(0, 8, 0, 0)
        }
        root.addView(emoji)
        root.addView(label)
        root.setOnClickListener { onClick(type) }
        return root
    }

    private fun dp(ctx: android.content.Context, v: Float): Float =
        v * ctx.resources.displayMetrics.density

    companion object {
        fun emojiFor(type: ConversionType): String = when (type) {
            ConversionType.PDF_TO_IMAGES -> "🖼️"
            ConversionType.PDF_TO_LONG_IMAGE -> "📜"
            ConversionType.PDF_TO_TXT -> "📝"
            ConversionType.PDF_TO_WORD -> "📘"
            ConversionType.PDF_TO_EXCEL -> "📊"
            ConversionType.PDF_TO_PPT -> "📑"
            ConversionType.TXT_TO_PDF -> "📄"
            ConversionType.IMAGES_TO_PDF -> "🖼️"
            ConversionType.LONG_IMAGE_TO_PDF -> "📜"
            ConversionType.WORD_TO_PDF -> "📘"
            ConversionType.EXCEL_TO_PDF -> "📊"
            ConversionType.PPT_TO_PDF -> "📑"
            ConversionType.MERGE -> "🔗"
            ConversionType.SPLIT -> "✂️"
            ConversionType.COMPRESS -> "🗜️"
            ConversionType.IMAGE_TO_ICON -> "🪄"
            ConversionType.IMAGE_COMPRESS -> "📉"
        }
    }
}
