package app.abeja.patches.gamespace.startanimation

import app.abeja.patches.gamespace.patchoptions.GameSpacePatchOptionsPatch
import app.abeja.patches.shared.Constants.GAME_SPACE
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS =
    "Lapp/abeja/extension/gamespace/StartAnimationSettings;"

private const val BGM_EXTENSION_CLASS =
    "Lapp/abeja/extension/gamespace/BackgroundMusicSettings;"

@Suppress("unused")
val GameSpaceStartAnimationPatch = bytecodePatch(
    name = "Custom start animation and background music",
    description = "Adds a patch features menu to Game Space settings for importing custom " +
            "start animation videos and background music. Pressing back while the start " +
            "animation plays skips it.",
) {
    compatibleWith(GAME_SPACE)

    dependsOn(GameSpacePatchOptionsPatch)

    extendWith("extensions/extension.mpe")

    execute {
        // Game key devices ignore back while the splash is shown. Use it to
        // skip the start animation when one is playing.
        val onBackPressedMethod = GameSpaceActivityOnBackPressedFingerprint.method
        val firstBackInstruction = onBackPressedMethod.implementation!!.instructions.first()

        onBackPressedMethod.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, Lcn/nubia/gamelauncher/activity/GameSpaceActivity;->mSplash:Lcn/nubia/gamelauncher/fragment/SplashFragment;
                invoke-static {v0}, $EXTENSION_CLASS->handleBackPress(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :original
                return-void
            """,
            ExternalLabel("original", firstBackInstruction),
        )

        // Use an imported track for the Game Space background music.
        val initMediaPlayerMethod = BgmHelperInitMediaPlayerFingerprint.method
        val setDataSourceIndex = initMediaPlayerMethod.instructions.indexOfFirst { instruction ->
            val reference = (instruction as? ReferenceInstruction)?.reference
            reference is MethodReference &&
                    reference.definingClass == "Landroid/media/MediaPlayer;" &&
                    reference.name == "setDataSource" &&
                    reference.parameterTypes == listOf(
                        "Landroid/content/Context;",
                        "Landroid/net/Uri;",
                    )
        }
        if (setDataSourceIndex < 0) {
            throw PatchException("Could not find the MediaPlayer setup in initMediaPlayer")
        }

        initMediaPlayerMethod.addInstructions(
            setDataSourceIndex,
            """
                invoke-static {v2, v3}, $BGM_EXTENSION_CLASS->resolveBgmUri(Landroid/content/Context;Landroid/net/Uri;)Landroid/net/Uri;
                move-result-object v3
            """,
        )

        // Keep the stock URL for wallpapers, but use the imported video while
        // the manager is in start animation mode.
        val getCurrentUrlMethod = WallpaperManagerGetCurrentUrlFingerprint.method
        val firstOriginalInstruction = getCurrentUrlMethod.implementation!!.instructions.first()

        getCurrentUrlMethod.addInstructionsWithLabels(
            0,
            """
                invoke-virtual {p0}, Lcn/nubia/common/wallpaper/WallpaperManager;->isStartAnim()Z
                move-result v0
                if-eqz v0, :original

                iget-object v0, p0, Lcn/nubia/common/wallpaper/WallpaperManager;->mContext:Landroid/content/Context;
                invoke-static {v0}, $EXTENSION_CLASS->getSelectedStartAnimationUrl(Landroid/content/Context;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :original
                return-object v0
            """,
            ExternalLabel("original", firstOriginalInstruction),
        )

        // The bundled timeout is 2600 ms. Use the duration of the imported
        // video so longer animations are not cut off.
        val showStartAnimMethod = SplashFragmentShowStartAnimFingerprint.method
        val timeoutIndex = showStartAnimMethod.instructions.indexOfFirst { instruction ->
            instruction.opcode == Opcode.CONST_WIDE_16 &&
                    (instruction as? NarrowLiteralInstruction)?.narrowLiteral == 2600
        }
        if (timeoutIndex < 0) {
            throw PatchException("Could not find the start animation timeout")
        }

        showStartAnimMethod.replaceInstruction(
            timeoutIndex,
            "invoke-static {}, Lcn/nubia/common/wallpaper/WallpaperManager;->getInstance()Lcn/nubia/common/wallpaper/WallpaperManager;",
        )
        showStartAnimMethod.addInstruction(
            timeoutIndex + 1,
            "move-result-object v4",
        )
        showStartAnimMethod.addInstruction(
            timeoutIndex + 2,
            "invoke-static {v4}, $EXTENSION_CLASS->getStartAnimationDurationMillis(Ljava/lang/Object;)J",
        )
        showStartAnimMethod.addInstruction(
            timeoutIndex + 3,
            "move-result-wide v2",
        )
    }
}
