package app.abeja.patches.gamespace.gamebackground

import app.morphe.patcher.Fingerprint

object WallpaperRealUpdateAtmosphereFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamelauncher/wallpaper/Wallpaper;",
    name = "realUpdateAtmosphere",
    returnType = "V",
    parameters = listOf("Landroid/graphics/Bitmap;", "Z"),
)

object GameBackgroundOtherOptionsFragmentOnCreateFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/basic/OtherOptionsFragment;",
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
)
