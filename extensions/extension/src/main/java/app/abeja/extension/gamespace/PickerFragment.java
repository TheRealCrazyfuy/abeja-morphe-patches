package app.abeja.extension.gamespace;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Headless fragment used to receive the result of the system document picker.
 *
 * <p>Using a fragment avoids registering a new Activity in the manifest, which
 * is not possible from a Morphe extension.</p>
 */
public final class PickerFragment extends Fragment {
    private static final String TAG = "GameSpacePicker";
    private static final String FRAGMENT_TAG = "app.abeja.gamespace.PickerFragment";
    private static final String SETTINGS_FRAGMENT_TAG = "OtherOptionsFragment";
    private static final int REQUEST_PICK_VIDEO = 0xABE1;

    public static void launch(FragmentActivity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }

        FragmentManager manager = activity.getSupportFragmentManager();
        if (manager.findFragmentByTag(FRAGMENT_TAG) != null) {
            return;
        }

        manager.beginTransaction()
                .add(new PickerFragment(), FRAGMENT_TAG)
                .commitAllowingStateLoss();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            openPicker();
        }
    }

    private void openPicker() {
        Intent documentIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        documentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        documentIntent.setType("video/*");
        documentIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(documentIntent, REQUEST_PICK_VIDEO);
            return;
        } catch (ActivityNotFoundException ignored) {
            // Fall back to the legacy picker below.
        }

        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentIntent.setType("video/*");
        contentIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivityForResult(contentIntent, REQUEST_PICK_VIDEO);
        } catch (ActivityNotFoundException | SecurityException exception) {
            Log.w(TAG, "No activity is available to pick a video", exception);
            Activity activity = getActivity();
            if (activity != null) {
                Toast.makeText(activity, "No video picker available",
                        Toast.LENGTH_SHORT).show();
            }
            finishAndRestore(false);
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_PICK_VIDEO) {
            return;
        }

        final FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            finishAndRestore(false);
            return;
        }

        final Uri uri = data.getData();
        try {
            activity.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
            // The copy happens immediately, so this permission is optional.
        }

        final Context appContext = activity.getApplicationContext();
        new Thread(() -> {
            File imported = copyToGallery(appContext, uri);
            activity.runOnUiThread(() -> {
                if (imported != null) {
                    StartAnimationSettings.setSelectedFile(activity, imported);
                    Toast.makeText(activity, "Imported: " + imported.getName(),
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "Unable to import the video",
                            Toast.LENGTH_SHORT).show();
                }

                finishAndRestore(imported != null);
            });
        }, "gamespace-start-animation-import").start();
    }

    private void finishAndRestore(boolean success) {
        FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        FragmentManager manager = activity.getSupportFragmentManager();
        FragmentTransaction transaction = manager.beginTransaction();

        Fragment picker = manager.findFragmentByTag(FRAGMENT_TAG);
        if (picker != null) {
            transaction.remove(picker);
        }

        // GameCenterActivity.onPause() hides the settings fragment while the
        // document picker is in front. Show it again when we come back.
        Fragment settings = manager.findFragmentByTag(SETTINGS_FRAGMENT_TAG);
        if (settings != null && settings.isHidden()) {
            transaction.show(settings);
        }

        transaction.commitAllowingStateLoss();
        StartAnimationSettings.onImportFinished(activity, success);
    }

    private static File copyToGallery(Context context, Uri uri) {
        if (context == null || uri == null) {
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        File directory = StartAnimationSettings.getGalleryDir(context);
        String name = sanitize(queryDisplayName(resolver, uri));
        String mimeType = null;
        try {
            mimeType = resolver.getType(uri);
        } catch (Throwable ignored) {
            // The extension fallback below is enough.
        }

        if (!hasVideoExtension(name)) {
            name = name + extensionForMimeType(mimeType);
        }

        File destination = new File(directory, name);
        if (destination.exists()) {
            destination = new File(directory, uniqueName(name));
        }

        try (InputStream input = resolver.openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                return null;
            }

            byte[] buffer = new byte[64 * 1024];
            long total = 0L;
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                total += read;
            }
            output.flush();

            if (total == 0L) {
                destination.delete();
                return null;
            }

            return destination;
        } catch (Throwable exception) {
            Log.w(TAG, "Unable to copy the selected video", exception);
            destination.delete();
            return null;
        }
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall back to the URI path below.
        }

        String lastSegment = uri.getLastPathSegment();
        return lastSegment == null ? "" : lastSegment;
    }

    private static String sanitize(String name) {
        String sanitized = name == null ? "" : name.trim();
        sanitized = sanitized.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        if (sanitized.isEmpty()) {
            sanitized = "start_animation_" + System.currentTimeMillis();
        }
        if (sanitized.length() > 96) {
            int dot = sanitized.lastIndexOf('.');
            if (dot > 0) {
                sanitized = sanitized.substring(0, 80) + sanitized.substring(dot);
            } else {
                sanitized = sanitized.substring(0, 96);
            }
        }
        return sanitized;
    }

    private static String uniqueName(String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        return base + "_" + System.currentTimeMillis() + extension;
    }

    private static boolean hasVideoExtension(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4")
                || lower.endsWith(".m4v")
                || lower.endsWith(".webm")
                || lower.endsWith(".mkv")
                || lower.endsWith(".mov")
                || lower.endsWith(".3gp")
                || lower.endsWith(".avi");
    }

    private static String extensionForMimeType(String mimeType) {
        if (mimeType == null) {
            return ".mp4";
        }

        switch (mimeType.toLowerCase(Locale.US)) {
            case "video/webm":
                return ".webm";
            case "video/x-matroska":
            case "video/mkv":
                return ".mkv";
            case "video/quicktime":
                return ".mov";
            case "video/3gpp":
                return ".3gp";
            case "video/x-msvideo":
                return ".avi";
            case "video/x-m4v":
                return ".m4v";
            default:
                return ".mp4";
        }
    }
}
