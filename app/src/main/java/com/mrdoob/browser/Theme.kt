package com.mrdoob.browser

import android.content.Context
import android.view.View
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.annotation.AttrRes
import com.google.android.material.color.MaterialColors

fun View.materialColor(@AttrRes attr: Int): Int = MaterialColors.getColor(this, attr)

fun View.materialColorHex(@AttrRes attr: Int): String =
    String.format("#%06X", 0xFFFFFF and materialColor(attr))

/**
 * One of the app's palette colours (R.color.nx_*), resolved through the current theme so that
 * Material You dynamic colours and the chosen accent apply. Status colours (recording, paused,
 * ok, idle) have no theme equivalent and keep their fixed values.
 */
fun Context.nx(@ColorRes id: Int): Int {
    val attr = when (id) {
        R.color.nx_primary -> com.google.android.material.R.attr.colorPrimary
        R.color.nx_on_primary -> com.google.android.material.R.attr.colorOnPrimary
        R.color.nx_primary_container -> com.google.android.material.R.attr.colorPrimaryContainer
        R.color.nx_on_primary_container -> com.google.android.material.R.attr.colorOnPrimaryContainer
        R.color.nx_secondary_container -> com.google.android.material.R.attr.colorSecondaryContainer
        R.color.nx_tertiary -> com.google.android.material.R.attr.colorTertiary
        R.color.nx_tertiary_container -> com.google.android.material.R.attr.colorTertiaryContainer
        R.color.nx_error -> com.google.android.material.R.attr.colorError
        R.color.nx_surface_variant -> com.google.android.material.R.attr.colorSurfaceVariant
        R.color.nx_on_surface -> com.google.android.material.R.attr.colorOnSurface
        R.color.nx_on_surface_variant -> com.google.android.material.R.attr.colorOnSurfaceVariant
        R.color.nx_outline_variant -> com.google.android.material.R.attr.colorOutlineVariant
        R.color.nx_container_lowest -> com.google.android.material.R.attr.colorSurfaceContainerLowest
        R.color.nx_container_low -> com.google.android.material.R.attr.colorSurfaceContainerLow
        R.color.nx_container -> com.google.android.material.R.attr.colorSurfaceContainer
        R.color.nx_container_high -> com.google.android.material.R.attr.colorSurfaceContainerHigh
        R.color.nx_container_highest -> com.google.android.material.R.attr.colorSurfaceContainerHighest
        else -> null
    }
    val fallback = ContextCompat.getColor(this, id)
    return if (attr == null) fallback else MaterialColors.getColor(this, attr, fallback)
}
