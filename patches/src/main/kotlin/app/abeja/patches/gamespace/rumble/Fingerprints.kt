package app.abeja.patches.gamespace.rumble

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

object WallpaperDoVibrateFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamelauncher/wallpaper/Wallpaper;",
    name = "doVibrate",
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = emptyList(),
)
