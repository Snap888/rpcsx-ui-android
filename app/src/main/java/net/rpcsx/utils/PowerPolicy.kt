package net.rpcsx.utils

import net.rpcsx.RPCSX
import net.rpcsx.utils.GeneralSettings.boolean

/**
 * App-side Android battery-saver (Clanker Settings). When on (default), it tells
 * the core to make low-power choices at the EFFECTIVE level - bypassing saved and
 * per-game configs, like the compile-thread cap:
 *  - force FIFO present (caps the GPU to the display refresh instead of rendering
 *    uncapped at full clock);
 *  - collapse the SPU GETLLAR busy-wait so idle reservation polls take the OS
 *    sleep instead of pinning a big core spinning.
 * The core symbol is null-guarded, so older cores simply ignore it. Off == stock.
 */
object PowerPolicy {
    private const val KEY = "power_save_mode"

    var enabled: Boolean
        get() = GeneralSettings[KEY].boolean(true)
        set(value) { GeneralSettings[KEY] = value }

    /** Push the current state to the core. Call at startup and on toggle. */
    fun apply() {
        runCatching { RPCSX.instance.setPowerSaveMode(enabled) }
    }
}
