// Based on the compose-miuix-ui/miuix example (Apache-2.0), itself adapted from Kyant0/AndroidLiquidGlass (Apache-2.0); ported via Miuzarte/ScrcpyForAndroid (Apache-2.0).
// Ported from miuix example: component/liquid/Vibrancy.kt (Apache 2.0).
package com.taocrypt.micmask.ui2.glass

import top.yukonga.miuix.kmp.blur.BackdropEffectScope
import top.yukonga.miuix.kmp.blur.colorControls

/** Lightweight stand-in for Kyant's `vibrancy()`. */
fun BackdropEffectScope.vibrancy() {
    colorControls(
        brightness = 0f,
        contrast = 1f,
        saturation = 1.5f,
    )
}
