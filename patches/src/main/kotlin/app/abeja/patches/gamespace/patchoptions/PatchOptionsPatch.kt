package app.abeja.patches.gamespace.patchoptions

import app.abeja.patches.shared.Constants.GAME_SPACE
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS = "Lapp/abeja/extension/gamespace/PatchOptions;"

@Suppress("unused")
val GameSpacePatchOptionsPatch = bytecodePatch(
    name = "Patch options section",
    description = "Adds a Patch options section to Game Space settings between Other " +
            "options and About game space, containing all options added by these patches.",
) {
    compatibleWith(GAME_SPACE)

    extendWith("extensions/extension.mpe")

    execute {
        // Add our category to the left-hand menu, right after Other options.
        val getShowCategory = GameCenterActivityGetShowCategoryFingerprint.method
        val returnIndex = getShowCategory.instructions.indexOfLast {
            it.opcode == Opcode.RETURN_OBJECT
        }
        if (returnIndex < 0) {
            throw PatchException("Could not find the return of getShowCategory")
        }
        getShowCategory.addInstructions(
            returnIndex,
            "invoke-static {v1}, $EXTENSION_CLASS->addCategory(Ljava/util/ArrayList;)V",
        )

        // Intercept selection of our category and show our own preference screen.
        // 17.0 calls this switchFragmentDirect; 16.5 calls it onItemClick.
        var switchPatched = false
        for (fingerprint in listOf(
            GameCenterActivitySwitchFragmentDirectFingerprint,
            GameCenterActivityOnItemClickFingerprint,
        )) {
            val method = try {
                fingerprint.method
            } catch (t: Throwable) {
                continue
            }
            val firstInstruction = method.implementation!!.instructions.first()
            method.addInstructionsWithLabels(
                0,
                """
                    invoke-static {p1, p0}, $EXTENSION_CLASS->handleSwitch(Ljava/lang/Class;Landroidx/fragment/app/FragmentActivity;)Z
                    move-result v0
                    if-eqz v0, :original
                    return-void
                """,
                ExternalLabel("original", firstInstruction),
            )
            switchPatched = true
            break
        }
        if (!switchPatched) {
            throw PatchException("Could not find the category selection method")
        }

        // Category titles come from string resources; override ours with a literal.
        val bindMethod = CategoryAdapterOnBindViewHolderFingerprint.method
        val bindReturnIndex = bindMethod.instructions.indexOfLast {
            it.opcode == Opcode.RETURN_VOID
        }
        if (bindReturnIndex < 0) {
            throw PatchException("Could not find the return of onBindViewHolder")
        }
        bindMethod.addInstructions(
            bindReturnIndex,
            "invoke-static {p0, p1}, $EXTENSION_CLASS->onBindCategory(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }
}
