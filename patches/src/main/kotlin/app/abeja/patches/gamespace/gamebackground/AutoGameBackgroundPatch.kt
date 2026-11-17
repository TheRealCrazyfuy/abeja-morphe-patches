package app.abeja.patches.gamespace.gamebackground

import app.abeja.patches.gamespace.patchoptions.GameSpacePatchOptionsPatch
import app.abeja.patches.shared.Constants.GAME_SPACE
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/abeja/extension/gamespace/AutoGameBackground;"

@Suppress("unused")
val GameSpaceAutoGameBackgroundPatch = bytecodePatch(
    name = "Game backgrounds from Google Play",
    description = "Adds a Game backgrounds option in Game Space settings that resolves " +
            "official backgrounds from Google Play for every added game. Updates are " +
            "manual only, so switching games keeps the stock behavior.",
) {
    compatibleWith(GAME_SPACE)

    dependsOn(GameSpacePatchOptionsPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // Some 16.5 builds recycle the previously shown atmosphere bitmap
        // here. That bitmap is also kept in Game Space's ImageCache (and
        // Glide's pool), so selecting another game can draw a recycled bitmap
        // and crash with "Canvas: trying to use a recycled bitmap". Let the GC
        // own it instead. Newer versions (e.g. 17.0) fixed this upstream and
        // have no recycle() call, so skip this step when it is absent.
        val realUpdateMethod = WallpaperRealUpdateAtmosphereFingerprint.method
        val recycleIndex = realUpdateMethod.instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference
            reference is MethodReference &&
                    reference.definingClass == "Landroid/graphics/Bitmap;" &&
                    reference.name == "recycle"
        }
        if (recycleIndex >= 0) {
            realUpdateMethod.replaceInstruction(
                recycleIndex,
                "invoke-static {}, $EXTENSION_CLASS->keepBitmap()V",
            )
        }
    }
}
