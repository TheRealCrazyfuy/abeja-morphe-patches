package app.abeja.patches.gamespace.rumble

import app.abeja.patches.gamespace.patchoptions.GameCenterActivityGetShowCategoryFingerprint
import app.abeja.patches.gamespace.patchoptions.GameSpacePatchOptionsPatch
import app.abeja.patches.shared.Constants.GAME_SPACE
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS = "Lapp/abeja/extension/gamespace/StartAnimationRumble;"

@Suppress("unused")
val GameSpaceStartAnimationRumblePatch = bytecodePatch(
    name = "Disable start animation rumble",
    description = "Adds a switch to Patch options that disables the vibration played while " +
            "the start animation is running.",
) {
    compatibleWith(GAME_SPACE)

    dependsOn(GameSpacePatchOptionsPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // Let the extension know this patch is active so its switch is visible.
        GameCenterActivityGetShowCategoryFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->markEnabled()V",
        )

        // Wallpaper.doVibrate() calls Util.doMyOsVibrateOfHe() with the device's
        // vibration resource id. Route that call through the extension so the
        // switch can suppress it while preserving the original behavior.
        val vibrateMethod = WallpaperDoVibrateFingerprint.method
        val vibrateCallIndex = vibrateMethod.instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference
            reference is MethodReference &&
                    reference.name == "doMyOsVibrateOfHe"
        }
        if (vibrateCallIndex < 0) {
            throw PatchException("Could not find the start animation vibration call")
        }
        vibrateMethod.replaceInstruction(
            vibrateCallIndex,
            "invoke-static {v0}, $EXTENSION_CLASS->doVibrateIfEnabled(I)V",
        )
    }
}
