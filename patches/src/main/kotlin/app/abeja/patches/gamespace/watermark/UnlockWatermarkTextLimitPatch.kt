package app.abeja.patches.gamespace.watermark

import app.abeja.patches.shared.Constants.GAME_SPACE
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val WATERMARK_WATCHER_CLASS =
    "Lcn/nubia/gamecenter/settings/watermark/WaterMarkWatcher;"
private const val ORIGINAL_LIMIT = 10
private const val UNLOCKED_LIMIT = 1000

/**
 * The watermark dialog creates a [WaterMarkWatcher] with a hard-coded limit of 10.
 * Replace that argument so the editor accepts much longer messages.
 */
private fun MutableMethod.unlockWatermarkLimit() {
    val methodInstructions = instructions
    val watcherInitIndex = methodInstructions.indexOfFirst { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference
        reference is MethodReference &&
                reference.definingClass == WATERMARK_WATCHER_CLASS &&
                reference.name == "<init>" &&
                reference.parameterTypes == listOf("Landroid/widget/EditText;", "I")
    }
    if (watcherInitIndex < 1) {
        throw PatchException("Could not find the watermark text watcher")
    }

    val limitIndex = watcherInitIndex - 1
    val limitInstruction = methodInstructions[limitIndex]
    if (limitInstruction.opcode != Opcode.CONST_16 ||
            (limitInstruction as? NarrowLiteralInstruction)?.narrowLiteral != ORIGINAL_LIMIT) {
        throw PatchException("Unexpected watermark text limit instruction")
    }

    val register = (methodInstructions[watcherInitIndex] as? FiveRegisterInstruction)?.registerE
        ?: throw PatchException("Could not read the watermark text limit register")
    replaceInstruction(limitIndex, "const/16 v$register, $UNLOCKED_LIMIT")
}

@Suppress("unused")
val UnlockWatermarkTextLimitPatch = bytecodePatch(
    name = "Unlock custom watermark text limit",
    description = "Raises the custom watermark text limit from 10 to 1000 characters.",
) {
    compatibleWith(GAME_SPACE)

    execute {
        WatermarkFragmentOnPreferenceTreeClickFingerprint.method.unlockWatermarkLimit()
    }
}
