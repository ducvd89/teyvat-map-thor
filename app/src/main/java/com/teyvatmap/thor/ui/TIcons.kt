package com.teyvatmap.thor.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.teyvatmap.thor.R

/** The app's own outline icon set (res/drawable/ic_*.xml, 24 dp, 2 dp stroke, round caps), tinted at the call site. */
object TIcons {
    @DrawableRes val Follow = R.drawable.ic_follow
    @DrawableRes val Retrack = R.drawable.ic_retrack
    @DrawableRes val Recenter = R.drawable.ic_recenter
    @DrawableRes val TrackingOn = R.drawable.ic_tracking_on
    @DrawableRes val TrackingOff = R.drawable.ic_tracking_off
    @DrawableRes val Filter = R.drawable.ic_filter
    @DrawableRes val Layers = R.drawable.ic_layers
    @DrawableRes val LayerUp = R.drawable.ic_layer_up
    @DrawableRes val LayerDown = R.drawable.ic_layer_down
    @DrawableRes val MapSwitch = R.drawable.ic_map_switch
    @DrawableRes val Search = R.drawable.ic_search
    @DrawableRes val Progress = R.drawable.ic_progress
    @DrawableRes val Settings = R.drawable.ic_settings
    @DrawableRes val OpenApp = R.drawable.ic_open_app
    @DrawableRes val GameMode = R.drawable.ic_game_mode
    @DrawableRes val SyncOk = R.drawable.ic_sync_ok
    @DrawableRes val SyncBusy = R.drawable.ic_sync_busy
    @DrawableRes val SyncError = R.drawable.ic_sync_error
    @DrawableRes val Pause = R.drawable.ic_pause
    @DrawableRes val Play = R.drawable.ic_play
    @DrawableRes val Close = R.drawable.ic_close
    @DrawableRes val Undo = R.drawable.ic_undo
    @DrawableRes val Found = R.drawable.ic_check_circle
    @DrawableRes val Eye = R.drawable.ic_eye
    @DrawableRes val EyeOff = R.drawable.ic_eye_off
    @DrawableRes val More = R.drawable.ic_more
    @DrawableRes val Nearby = R.drawable.ic_nearby
    @DrawableRes val ChevronRight = R.drawable.ic_chevron_right
    @DrawableRes val ChevronDown = R.drawable.ic_chevron_down
    @DrawableRes val Back = R.drawable.ic_back
    @DrawableRes val Calibrate = R.drawable.ic_calibrate
    @DrawableRes val Diagnostics = R.drawable.ic_diagnostics
    @DrawableRes val Debug = R.drawable.ic_debug
    @DrawableRes val Download = R.drawable.ic_download
    @DrawableRes val Trash = R.drawable.ic_trash
    @DrawableRes val Edit = R.drawable.ic_edit
    @DrawableRes val Pin = R.drawable.ic_pin
}

@Composable
fun TIcon(
    @DrawableRes res: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
) {
    Icon(painterResource(res), contentDescription, modifier.size(size), tint)
}
