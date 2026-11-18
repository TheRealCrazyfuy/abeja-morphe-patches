package app.abeja.patches.gamespace.watermark

import app.morphe.patcher.Fingerprint

object WatermarkFragmentOnPreferenceTreeClickFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/watermark/WatermarkFragment;",
    name = "onPreferenceTreeClick",
    returnType = "Z",
    parameters = listOf("Lcn/nubia/gamecenter/settings/compatible/Preference;"),
)
