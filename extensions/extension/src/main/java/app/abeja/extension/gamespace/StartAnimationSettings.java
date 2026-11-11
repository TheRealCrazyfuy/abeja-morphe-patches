package app.abeja.extension.gamespace;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.Toast;

import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Settings entry and selection logic for a custom Game Space start animation.
 *
 * <p>Imported videos are copied into {@code files/start_animations} so they
 * persist across reboots and do not require any storage permission.</p>
 */
public final class StartAnimationSettings {
    private static final String TAG = "StartAnimationSettings";
    private static final String PREFS_NAME = "game_space_start_animation";
    private static final String KEY_SELECTED = "selected_video";
    private static final String KEY_PREFERENCE = "abeja_custom_start_animation";
    private static final String DIR_NAME = "start_animations";
    private static final long DEFAULT_TIMEOUT_MS = 2600L;
    private static final long DURATION_BUFFER_MS = 400L;
    private static final String DEFAULT_SUMMARY = "Default animation";

    private static WeakReference<Preference> sPreference = new WeakReference<>(null);

    private StartAnimationSettings() {
    }

    /**
     * Called from the patched {@code OtherOptionsFragment.onCreate}.
     */
    public static void addPreference(PreferenceScreen screen) {
        if (screen == null) {
            return;
        }

        Context context = screen.getContext();
        if (context == null) {
            return;
        }

        if (screen.findPreference(KEY_PREFERENCE) != null) {
            return;
        }

        try {
            Preference preference = createPreference(context);
            preference.setKey(KEY_PREFERENCE);
            preference.setTitle("Start animation video");
            preference.setSummary(buildSummary(context));
            preference.setPersistent(false);
            preference.setOnPreferenceClickListener(p -> {
                showGalleryDialog(context);
                return true;
            });
            screen.addPreference(preference);
            sPreference = new WeakReference<>(preference);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to add the start animation preference", t);
        }
    }

    /**
     * Called from the patched {@code WallpaperManager.getCurrentUrl()}.
     *
     * @return the custom video URL, or {@code null} to keep the default URL.
     */
    public static String getSelectedStartAnimationUrl(Context context) {
        try {
            File file = getSelectedFile(context);
            return file == null ? null : Uri.fromFile(file).toString();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to resolve the selected start animation", t);
            return null;
        }
    }

    /**
     * Called from the patched {@code SplashFragment.showStartAnim()}.
     *
     * <p>The original timeout is 2600 ms, which cuts off longer custom videos.
     * When a custom video is selected the timeout is based on its duration.</p>
     *
     * @param wallpaperManager the {@code cn.nubia.common.wallpaper.WallpaperManager}
     *                         instance, used only to obtain an application context.
     */
    public static long getStartAnimationDurationMillis(Object wallpaperManager) {
        Context context = getContext(wallpaperManager);
        if (context == null) {
            return DEFAULT_TIMEOUT_MS;
        }

        File file = getSelectedFile(context);
        if (file == null) {
            return DEFAULT_TIMEOUT_MS;
        }

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (duration != null) {
                long millis = Long.parseLong(duration);
                if (millis > 0L) {
                    return millis + DURATION_BUFFER_MS;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to read the start animation duration", t);
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
                // Nothing else to do.
            }
        }

        return DEFAULT_TIMEOUT_MS;
    }

    static void onImportFinished(FragmentActivity activity, boolean success) {
        if (activity == null || !success) {
            return;
        }

        updatePreferenceSummary(activity);

        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        activity.getWindow().getDecorView().postDelayed(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                showGalleryDialog(activity);
            }
        }, 350L);
    }

    private static Preference createPreference(Context context) {
        try {
            Class<?> clazz = Class.forName(
                    "cn.nubia.gamecenter.settings.preference.GameCenterPreference");
            Constructor<?> constructor = clazz.getConstructor(
                    Context.class, AttributeSet.class);
            Object preference = constructor.newInstance(context, null);
            if (preference instanceof Preference) {
                return (Preference) preference;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Falling back to the default preference row", t);
        }

        return new Preference(context);
    }

    private static void showGalleryDialog(Context context) {
        try {
            final List<File> files = getGalleryFiles(context);
            final File selected = getSelectedFile(context);
            final String[] items = new String[files.size() + 2];

            items[0] = selected == null
                    ? "Default animation (selected)"
                    : "Default animation";

            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                boolean isSelected = selected != null
                        && selected.getAbsolutePath().equals(file.getAbsolutePath());
                items[i + 1] = file.getName() + (isSelected ? "  (selected)" : "");
            }

            items[files.size() + 1] = "Import video...";

            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("Start animation");
            builder.setItems(items, (dialog, which) -> {
                if (which == 0) {
                    setSelectedFile(context, null);
                    toast(context, "Default start animation selected");
                } else if (which == files.size() + 1) {
                    startPicker(context);
                } else {
                    File file = files.get(which - 1);
                    setSelectedFile(context, file);
                    toast(context, "Selected: " + file.getName());
                }
            });
            builder.setNegativeButton("Close", null);
            builder.show();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to show the start animation dialog", t);
        }
    }

    private static void startPicker(Context context) {
        if (context instanceof FragmentActivity) {
            PickerFragment.launch((FragmentActivity) context);
        } else {
            toast(context, "Unable to open the video picker");
        }
    }

    private static CharSequence buildSummary(Context context) {
        File file = getSelectedFile(context);
        return file == null ? DEFAULT_SUMMARY : "Custom: " + file.getName();
    }

    private static void updatePreferenceSummary(Context context) {
        Preference preference = sPreference.get();
        if (preference == null || context == null) {
            return;
        }

        try {
            preference.setSummary(buildSummary(context));
        } catch (Throwable t) {
            Log.w(TAG, "Unable to update the preference summary", t);
        }
    }

    private static SharedPreferences preferences(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    static File getSelectedFile(Context context) {
        if (context == null) {
            return null;
        }

        String path = preferences(context).getString(KEY_SELECTED, null);
        if (TextUtils.isEmpty(path)) {
            return null;
        }

        File file = new File(path);
        if (!file.isFile()) {
            preferences(context).edit().remove(KEY_SELECTED).apply();
            return null;
        }

        return file;
    }

    static void setSelectedFile(Context context, File file) {
        if (context == null) {
            return;
        }

        SharedPreferences preferences = preferences(context);
        if (file == null) {
            preferences.edit().remove(KEY_SELECTED).apply();
        } else {
            preferences.edit().putString(KEY_SELECTED, file.getAbsolutePath()).apply();
        }

        updatePreferenceSummary(context);
    }

    static File getGalleryDir(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }

        File dir = new File(appContext.getFilesDir(), DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Unable to create the start animation gallery directory");
        }
        return dir;
    }

    private static List<File> getGalleryFiles(Context context) {
        File dir = getGalleryDir(context);
        File[] files = dir.listFiles(file -> file.isFile() && isVideo(file.getName()));
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }

        List<File> list = new ArrayList<>(Arrays.asList(files));
        list.sort((first, second) ->
                Long.compare(second.lastModified(), first.lastModified()));
        return list;
    }

    private static boolean isVideo(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".mp4")
                || lower.endsWith(".m4v")
                || lower.endsWith(".webm")
                || lower.endsWith(".mkv")
                || lower.endsWith(".mov")
                || lower.endsWith(".3gp")
                || lower.endsWith(".avi");
    }

    private static Context getContext(Object wallpaperManager) {
        if (wallpaperManager == null) {
            return null;
        }

        try {
            Field field = wallpaperManager.getClass().getDeclaredField("mContext");
            field.setAccessible(true);
            Object value = field.get(wallpaperManager);
            if (value instanceof Context) {
                return (Context) value;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to obtain the WallpaperManager context", t);
        }

        // Last resort for unusual ROM builds.
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to obtain the application context", t);
        }

        return null;
    }

    private static void toast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to show toast", t);
        }
    }
}
