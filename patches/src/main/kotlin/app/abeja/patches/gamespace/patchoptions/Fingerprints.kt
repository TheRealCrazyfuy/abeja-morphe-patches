package app.abeja.patches.gamespace.patchoptions

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

object GameCenterActivityGetShowCategoryFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/GameCenterActivity;",
    name = "getShowCategory",
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "Ljava/util/ArrayList;",
    parameters = emptyList(),
)

object GameCenterActivitySwitchFragmentDirectFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/GameCenterActivity;",
    name = "switchFragmentDirect",
    accessFlags = listOf(AccessFlags.PRIVATE),
    returnType = "V",
    parameters = listOf("Ljava/lang/Class;"),
)

object GameCenterActivityOnItemClickFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/GameCenterActivity;",
    name = "onItemClick",
    returnType = "V",
    parameters = listOf("Ljava/lang/Class;"),
)

object CategoryAdapterOnBindViewHolderFingerprint : Fingerprint(
    definingClass = "Lcn/nubia/gamecenter/settings/CategoryAdapter;",
    name = "onBindViewHolder",
    returnType = "V",
    parameters = listOf(
        "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;",
        "I",
    ),
)
