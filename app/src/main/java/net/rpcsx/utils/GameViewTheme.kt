package net.rpcsx.utils

import androidx.compose.runtime.mutableStateOf
import net.rpcsx.utils.GeneralSettings.boolean
import net.rpcsx.utils.GeneralSettings.int

/**
 * Persisted, opt-in cosmetic theming for the library game tiles (the home/list
 * view). Everything defaults OFF / neutral, so a fresh install looks unchanged.
 * Applied as a clip + border on the tile Card in GamesScreen - it never touches
 * the cover/icon aspect ratio (that lives on the inner image box), so logos are
 * not distorted.
 *
 * Backed by Compose snapshot state (like ThemeState) so the library grid
 * recomposes live when a value changes, instead of staying stale until the
 * screen is recreated.
 */
object GameViewTheme {
    private const val ACCENT = 0xFFBBADDE.toInt()

    private val _rounded = mutableStateOf(GeneralSettings["gv_rounded"].boolean(false))
    private val _radius = mutableStateOf(GeneralSettings["gv_radius_dp"].int(16))
    private val _border = mutableStateOf(GeneralSettings["gv_border"].boolean(false))
    private val _borderW = mutableStateOf(GeneralSettings["gv_border_w_dp"].int(2))
    private val _borderColor = mutableStateOf(GeneralSettings["gv_border_color"].int(ACCENT))

    /** Round the corners of every game tile in the library grid. */
    var roundedCorners: Boolean
        get() = _rounded.value
        set(v) { _rounded.value = v; GeneralSettings["gv_rounded"] = v }

    /** Tile corner radius in dp. */
    var cornerRadiusDp: Int
        get() = _radius.value
        set(v) { val c = v.coerceIn(0, 64); _radius.value = c; GeneralSettings["gv_radius_dp"] = c }

    /** Draw a coloured outline around each tile. */
    var border: Boolean
        get() = _border.value
        set(v) { _border.value = v; GeneralSettings["gv_border"] = v }

    var borderWidthDp: Int
        get() = _borderW.value
        set(v) { val c = v.coerceIn(0, 16); _borderW.value = c; GeneralSettings["gv_border_w_dp"] = c }

    var borderColor: Int
        get() = _borderColor.value
        set(v) { _borderColor.value = v; GeneralSettings["gv_border_color"] = v }
}
