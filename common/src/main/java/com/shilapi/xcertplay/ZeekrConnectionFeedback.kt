package com.shilapi.xcertplay

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

/** One real session state, expressed with text plus an icon/progress indicator. */
internal class ZeekrConnectionFeedback(context: Context, private val palette: DiPlayPalette) : LinearLayout(context) {
    enum class Phase { EMPTY, READY, CONNECTING, CONNECTED, ERROR }
    val title = TextView(context).apply {
        textSize = 28f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(palette.primaryText)
    }
    private val icon = ImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.CENTER_INSIDE
    }
    private val progress = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(palette.accent)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(ZeekrHmiTokens.BLOCK_GAP_DP), 0, dp(ZeekrHmiTokens.BLOCK_GAP_DP))
        val indicator = android.widget.FrameLayout(context).apply {
            addView(icon, android.widget.FrameLayout.LayoutParams(-1, -1))
            addView(progress, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        addView(indicator, LayoutParams(dp(ZeekrHmiTokens.FEEDBACK_ICON_DP), dp(ZeekrHmiTokens.FEEDBACK_ICON_DP)).apply {
            marginEnd = dp(ZeekrHmiTokens.BLOCK_GAP_DP)
        })
        addView(title, LayoutParams(0, -2, 1f))
        show(Phase.EMPTY)
    }
    fun show(phase: Phase) {
        progress.visibility = if (phase == Phase.CONNECTING) View.VISIBLE else View.GONE
        icon.visibility = if (phase == Phase.CONNECTING) View.GONE else View.VISIBLE
        icon.setImageResource(when (phase) {
            Phase.CONNECTED -> R.drawable.ic_kr_check
            Phase.ERROR -> R.drawable.ic_kr_alert
            Phase.EMPTY -> R.drawable.ic_kr_phone
            else -> R.drawable.ic_dp_connection
        })
        icon.imageTintList = ColorStateList.valueOf(when (phase) {
            Phase.CONNECTED -> palette.success
            Phase.ERROR -> palette.danger
            Phase.CONNECTING -> palette.accent
            else -> palette.secondaryText
        })
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}
