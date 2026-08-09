package com.brianshih.mopria.android.scanprint.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Whether the current screen is wide enough for a tablet layout (≥ 600dp).
 * Used to switch between bottom NavigationBar (phone) and NavigationRail (tablet),
 * and to enable dual-pane layouts on larger screens.
 */
@Composable
fun isTabletLayout(): Boolean = LocalConfiguration.current.screenWidthDp >= 600
