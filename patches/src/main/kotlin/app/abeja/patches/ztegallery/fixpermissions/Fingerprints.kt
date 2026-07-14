package app.abeja.patches.ztegallery.fixpermissions

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

object MultiGalleryOnResumeFingerprint : Fingerprint(
    definingClass = "Lcom/zte/gallery3d/activity/execute/MultiGallery;",
    name = "onResume",
    accessFlags = listOf(AccessFlags.PROTECTED),
    returnType = "V",
    parameters = emptyList(),
)

object PhotoGalleryGetRequestArrayFingerprint : Fingerprint(
    definingClass = "Lcom/zte/gallery3d/activity/execute/PhotoGallery;",
    name = "getRequestArray",
    returnType = "[Ljava/lang/String;",
)

object PermissionCheckerHelperGetRequestArrayFingerprint : Fingerprint(
    definingClass = "Lcom/zte/gallery3d/activity/execute/helper/PermissionCheckerHelper;",
    name = "getRequestArray",
    returnType = "[Ljava/lang/String;",
)

object PreViewAlbumActivityGetRequestArrayFingerprint : Fingerprint(
    definingClass = "Lcom/zte/gallery3d/activity/execute/PreViewAlbumActivity;",
    name = "getRequestArray",
    returnType = "[Ljava/lang/String;",
)

object SmartBusinessGalleryGetRequestArrayFingerprint : Fingerprint(
    definingClass = "Lcom/zte/search/page/SmartBusinessGallery;",
    name = "getRequestArray",
    returnType = "[Ljava/lang/String;",
)