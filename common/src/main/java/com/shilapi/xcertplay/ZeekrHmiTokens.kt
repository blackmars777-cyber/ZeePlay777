package com.shilapi.xcertplay

/** App-owned OS 7.2-inspired tokens, derived from the supplied non-official HMI guide v1.1.
 * All dimensions are dp and type sizes are sp. WindowInsets stay owned by the activity.
 */
internal object ZeekrHmiTokens {
    const val CARD_RADIUS_DP = 20
    const val BUTTON_RADIUS_DP = 14
    const val CARD_PADDING_DP = 24
    const val CONTROL_VERTICAL_INSET_DP = 8
    const val BLOCK_GAP_DP = 16
    const val HOME_COLUMNS_GAP_DP = 20
    const val NOTICE_PADDING_DP = 16
    const val NOTICE_RADIUS_DP = 14
    const val HEADER_LABEL_MIN_WIDTH_DP = 360
    const val PAGE_INSET_COMPACT_DP = 12
    const val PAGE_INSET_FULL_DP = 32
    const val INLINE_ACTION_MAX_DP = 240
    const val QR_MAX_SIDE_DP = 240
    const val QR_FRAME_INSET_DP = 12
    const val ICON_DP = 28
    const val FEEDBACK_ICON_DP = 32
    const val MIN_TOUCH_DP = 60
    const val RAIL_BASE_WIDTH_DP = 192
    const val RAIL_MAX_WIDTH_DP = 288
    const val SECTION_TITLE_SP = 18
    const val PAGE_TITLE_SP = 24
    const val HEADER_TITLE_SP = 20
    const val GROUP_HEADING_GAP_DP = 8
    const val DIVIDER_DP = 1
    fun railWidthDp(fontScale: Float) = (RAIL_BASE_WIDTH_DP * fontScale).toInt().coerceIn(RAIL_BASE_WIDTH_DP, RAIL_MAX_WIDTH_DP)
    const val PAGE_FADE_MS = 180L
    const val WARNING_BACKGROUND_LIGHT = 0xfffff0e1.toInt()
    const val WARNING_BACKGROUND_DARK = 0xff3c2b21.toInt()

    fun qrSideDp(windowWidthDp: Int, compact: Boolean): Int =
        (windowWidthDp - 2 * (if (compact) PAGE_INSET_COMPACT_DP else PAGE_INSET_FULL_DP) -
            2 * CARD_PADDING_DP - 2 * QR_FRAME_INSET_DP).coerceIn(1, QR_MAX_SIDE_DP)

    val dark = DiPlayPalette.DARK.copy(
        background = 0xff111316.toInt(), surface = 0xff222428.toInt(), button = 0xff303236.toInt(),
        outline = 0xff3a3d42.toInt(), primaryText = 0xfff4f4f5.toInt(), secondaryText = 0xffa9abb2.toInt(),
        accent = 0xffe5e6e8.toInt(), onAccent = 0xff17191d.toInt(), accentTrack = 0xffe5e6e8.toInt(),
        trackOff = 0xff3a3d42.toInt(), railSelected = 0xff303236.toInt(), railSelectedOutline = 0xff3a3d42.toInt(),
        warning = 0xffffb06d.toInt(), success = 0xff5ebe93.toInt(), danger = 0xfff37c6d.toInt(),
        focusRing = 0xfff08a43.toInt(), ripple = 0x24ffffff, systemBar = 0xff111316.toInt(),
    )
    // Body/action text variants are darker than the guide's suggested fills to retain 4.5:1 contrast.
    val light = DiPlayPalette.LIGHT.copy(
        background = 0xfff1f0f2.toInt(), surface = 0xfff8f8f9.toInt(), button = 0xffeaebed.toInt(),
        outline = 0xffe2e3e6.toInt(), primaryText = 0xff191b1f.toInt(), secondaryText = 0xff676b73.toInt(),
        accent = 0xff414244.toInt(), onAccent = 0xffffffff.toInt(), accentTrack = 0xff414244.toInt(),
        trackOff = 0xff92959b.toInt(), railSelected = 0xffe4e5e7.toInt(), railSelectedOutline = 0xffe2e3e6.toInt(),
        warning = 0xffa95411.toInt(), success = 0xff1d7859.toInt(), danger = 0xffbd493b.toInt(),
        focusRing = 0xffda681c.toInt(), ripple = 0x18191b1f, systemBar = 0xfff1f0f2.toInt(),
    )
}
