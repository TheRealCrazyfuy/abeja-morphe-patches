package app.abeja.extension.gamespace;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Game Space background resolver.
 *
 * <p>The stock Game Space atmosphere fetcher only talks to Nubia's API, which
 * is effectively unusable outside the Chinese region. This class resolves the
 * same per-game data from the public Google Play listing and writes it through
 * Game Space's own AppListItemBean/AppAddModel path, so no new storage format
 * or UI is needed.</p>
 *
 * <p>Updates are manual only: the settings row triggers a refresh on demand.
 * The stock game-switch hooks are left untouched to avoid interfering with
 * Game Space's own refresh flow.</p>
 */
public final class AutoGameBackground {
    private static final String TAG = "AutoGameBackground";

    private static final String PREFS_NAME = "game_space_backgrounds";
    private static final String KEY_PREFERENCE = "abeja_game_backgrounds";
    private static final String URL_PREFIX = "url_";
    private static final String TIME_PREFIX = "time_";
    // Original atmosphere state, saved before we overwrite it so it can be restored.
    private static final String ORIG_TYPE_PREFIX = "orig_type_";
    private static final String ORIG_NET_PREFIX = "orig_net_";
    private static final String ORIG_CROP_PREFIX = "orig_crop_";
    private static final String ORIG_HIGH_PREFIX = "orig_high_";
    private static final String ORIG_LAST_TIME_PREFIX = "orig_last_time_";
    private static final String ORIG_UPDATE_TIME_PREFIX = "orig_update_time_";

    private static final String TYPE_NET = "net";
    private static final long CACHE_TTL_MS = TimeUnit.DAYS.toMillis(7);
    private static final int MAX_HTML_BYTES = 4 * 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;

    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; NX729J) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36";

    private static final Pattern LANDSCAPE_SCREENSHOT = Pattern.compile(
            "https://play-lh\\.googleusercontent\\.com/[^\"'\\s<>]+=w1052-h592");
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta\\s+property=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE_REVERSED = Pattern.compile(
            "<meta\\s+content=[\"']([^\"']+)[\"']\\s+property=[\"']og:image[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TWITTER_IMAGE = Pattern.compile(
            "<meta\\s+name=[\"']twitter:image[\"']\\s+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "gamespace-auto-background");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static java.lang.ref.WeakReference<Preference> sPreference =
            new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<Dialog> sFetchDialog =
            new java.lang.ref.WeakReference<>(null);

    private AutoGameBackground() {
    }

    /**
     * No-op stand-in for the stock atmosphere bitmap recycle call. Game Space
     * keeps the displayed bitmap in its own cache, so recycling it makes the
     * next game selection draw a recycled bitmap. Let the GC reclaim it instead.
     */
    public static void keepBitmap() {
    }

    /**
     * Adds the row to Game Space's "Other options" settings screen.
     */
    public static void addPreference(PreferenceScreen screen) {
        if (screen == null || screen.findPreference(KEY_PREFERENCE) != null) {
            return;
        }
        Context context = screen.getContext();
        if (context == null) {
            return;
        }

        try {
            Preference preference = createPreference(context);
            preference.setKey(KEY_PREFERENCE);
            preference.setTitle("Game backgrounds");
            preference.setSummary(buildSummary(context));
            preference.setPersistent(false);
            preference.setOnPreferenceClickListener(p -> {
                showDialog(context);
                return true;
            });
            screen.addPreference(preference);
            sPreference = new java.lang.ref.WeakReference<>(preference);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to add the game backgrounds preference", t);
        }
    }

    /**
     * Game Space styles its own setting rows with GameCenterPreference. Use it
     * when available so this row matches the other options; fall back to a
     * plain Preference on builds without it.
     */
    private static Preference createPreference(Context context) {
        try {
            Class<?> clazz = Class.forName(
                    "cn.nubia.gamecenter.settings.preference.GameCenterPreference");
            Constructor<?> constructor = clazz.getConstructor(Context.class, AttributeSet.class);
            Object preference = constructor.newInstance(context, null);
            if (preference instanceof Preference) {
                return (Preference) preference;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Falling back to the default preference row", t);
        }
        return new Preference(context);
    }

    private static void showDialog(Context context) {
        String[] options = new String[]{
                "Update all backgrounds now",
                "Restore default backgrounds",
        };

        new AlertDialog.Builder(context)
                .setTitle("Game backgrounds")
                .setItems(options, (DialogInterface dialog, int which) -> {
                    if (which == 0) {
                        refreshAllNow(context);
                    } else if (which == 1) {
                        clearCache(context);
                        refreshPreferenceSummary(context);
                        toast(context, "Backgrounds restored to default");
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    /**
     * Settings action: force a fresh Play lookup for every added game.
     */
    private static void refreshAllNow(Context context) {
        try {
            Object model = getAppAddModel(context);
            final List<String> packages = collectPackages(model);
            if (packages.isEmpty()) {
                toast(context, "No games added to Game Space yet");
                return;
            }

            showFetchDialog(context);

            EXECUTOR.execute(() -> {
                long start = System.currentTimeMillis();
                int updated = updatePackages(context, model, packages, true);
                final int result = updated;
                final int total = packages.size();
                // Keep the spinner up long enough to be seen even when every
                // lookup is served from cache or fails fast.
                final long delay = Math.max(0L, 1000L - (System.currentTimeMillis() - start));
                MAIN_HANDLER.postDelayed(() -> {
                    dismissFetchDialog();
                    toast(context, "Updated " + result + " of " + total + " backgrounds");
                    refreshPreferenceSummary(context);
                }, delay);
            });
        } catch (Throwable t) {
            Log.w(TAG, "Unable to refresh all backgrounds", t);
            dismissFetchDialog();
            toast(context, "Could not start the background update");
        }
    }

    /**
     * A simple, non-blocking progress dialog with a spinner and a label. The
     * stock upgrade layout relies on activity-specific theming and constraint
     * sizing, so reusing it in a plain dialog made its buttons overlap the
     * text; a small self-contained view avoids that entirely.
     */
    private static void showFetchDialog(Context context) {
        dismissFetchDialog();
        try {
            int spinnerSize = dp(context, 48);
            int labelGap = dp(context, 16);
            int padding = dp(context, 32);

            ProgressBar spinner = new ProgressBar(context);
            spinner.setIndeterminate(true);
            LinearLayout.LayoutParams spinnerParams =
                    new LinearLayout.LayoutParams(spinnerSize, spinnerSize);
            spinnerParams.gravity = Gravity.CENTER;
            spinner.setLayoutParams(spinnerParams);

            TextView label = new TextView(context);
            label.setText("Fetching background images");
            label.setTextSize(16f);
            label.setGravity(Gravity.CENTER);
            label.setPadding(0, labelGap, 0, 0);
            label.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            LinearLayout content = new LinearLayout(context);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER);
            content.setPadding(padding, padding, padding, padding);
            content.addView(spinner);
            content.addView(label);

            Dialog dialog = new AlertDialog.Builder(context)
                    .setView(content)
                    .setCancelable(false)
                    .create();
            dialog.show();
            sFetchDialog = new java.lang.ref.WeakReference<>(dialog);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to show the background fetch spinner", t);
            dismissFetchDialog();
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void dismissFetchDialog() {
        Dialog dialog = sFetchDialog.get();
        sFetchDialog = new java.lang.ref.WeakReference<>(null);
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Throwable ignored) {
                // The hosting activity may already be gone.
            }
        }
    }

    private static int updatePackages(Context context, Object model, List<String> packages,
                                      boolean force) {
        int updated = 0;
        for (String pkg : packages) {
            try {
                String url = resolveUrl(context, pkg, force);
                if (url != null && applyUrl(context, model, pkg, url)) {
                    updated++;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Unable to update background for " + pkg, t);
            }
        }
        Log.i(TAG, "Updated " + updated + " of " + packages.size() + " backgrounds");
        return updated;
    }

    private static String resolveUrl(Context context, String pkg, boolean force) {
        String cached = prefs(context).getString(URL_PREFIX + pkg, null);
        long cachedAt = prefs(context).getLong(TIME_PREFIX + pkg, 0L);

        if (!force && cached != null && System.currentTimeMillis() - cachedAt < CACHE_TTL_MS) {
            return cached;
        }

        try {
            String resolved = fetchPlayBanner(pkg);
            if (!isEmpty(resolved)) {
                prefs(context).edit()
                        .putString(URL_PREFIX + pkg, resolved)
                        .putLong(TIME_PREFIX + pkg, System.currentTimeMillis())
                        .apply();
                return resolved;
            }
        } catch (Throwable t) {
            Log.w(TAG, "Google Play lookup failed for " + pkg, t);
        }
        return cached;
    }

    private static String fetchPlayBanner(String pkg) throws Exception {
        String pageUrl = "https://play.google.com/store/apps/details?id=" +
                URLEncoder.encode(pkg, "UTF-8") + "&hl=en&gl=US";
        HttpURLConnection connection = (HttpURLConnection) new URL(pageUrl).openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept-Language", "en-US,en;q=0.9");

            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("Play returned HTTP " + status);
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            InputStream input = connection.getInputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) != -1 && total < MAX_HTML_BYTES) {
                output.write(buffer, 0, read);
                total += read;
            }
            input.close();

            String html = new String(output.toByteArray(), StandardCharsets.UTF_8);
            Matcher landscape = LANDSCAPE_SCREENSHOT.matcher(html);
            if (landscape.find()) {
                return landscape.group();
            }
            Matcher twitter = TWITTER_IMAGE.matcher(html);
            if (twitter.find()) {
                return unescapeHtml(twitter.group(1));
            }
            Matcher og = OG_IMAGE.matcher(html);
            if (og.find()) {
                return unescapeHtml(og.group(1));
            }
            Matcher ogReversed = OG_IMAGE_REVERSED.matcher(html);
            if (ogReversed.find()) {
                return unescapeHtml(ogReversed.group(1));
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    private static boolean applyUrl(Context context, Object model, String pkg, String url) {
        try {
            Object bean = findBean(model, pkg);
            if (bean == null) {
                Log.w(TAG, "No added-game bean found for " + pkg);
                return false;
            }

            String current = callString(bean, "getAtmosphereUrl");
            if (url.equals(current)) {
                // Still refresh the stock interval timestamp so future calls do not
                // keep scheduling redundant work.
                invokeByNameAndParamCount(bean, "updateAtmosphereTime", 0);
                invokeByNameAndParamCount(bean, "setLastUpdateUrlTime", 1,
                        System.currentTimeMillis());
                return true;
            }

            Method setImageUrl = findMethod(bean.getClass(), "setImageUrl", 2);
            if (setImageUrl == null) {
                Log.w(TAG, "AppListItemBean.setImageUrl(String, String) not found");
                return false;
            }
            setImageUrl.setAccessible(true);
            // Remember the stock values once, before the first overwrite, so a
            // later "Restore default backgrounds" can put them back.
            saveOriginalState(context, bean, pkg);
            setImageUrl.invoke(bean, url, TYPE_NET);

            invokeByNameAndParamCount(bean, "updateAtmosphereTime", 0);
            invokeByNameAndParamCount(bean, "setLastUpdateUrlTime", 1,
                    System.currentTimeMillis());
            invokeWithArg(model, "updateAppItemBeanInAppAddDB", bean);

            MAIN_HANDLER.post(() -> invokeByNameAndParamCount(model, "notifyRefreshRunnable", 0));
            Log.i(TAG, "Set background for " + pkg + " -> " + url);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to apply background for " + pkg, t);
            return false;
        }
    }

    private static List<String> collectPackages(Object model) {
        List<String> result = new ArrayList<>();
        if (model == null) {
            return result;
        }

        Object list = invokeByNameAndParamCount(model, "getGameList", 0);
        if (!(list instanceof Iterable)) {
            list = invokeByNameAndParamCount(model, "getAppAddedList", 0);
        }
        if (!(list instanceof Iterable)) {
            Log.w(TAG, "Unable to read the added-game list");
            return result;
        }

        for (Object bean : (Iterable<?>) list) {
            String pkg = normalizePackage(callString(bean, "getPackageName"));
            if (!isEmpty(pkg) && !result.contains(pkg)) {
                result.add(pkg);
            }
        }
        return result;
    }

    private static Object findBean(Object model, String pkg) {
        Object list = invokeByNameAndParamCount(model, "getGameList", 0);
        if (!(list instanceof Iterable)) {
            list = invokeByNameAndParamCount(model, "getAppAddedList", 0);
        }
        if (!(list instanceof Iterable)) {
            return null;
        }

        for (Object bean : (Iterable<?>) list) {
            if (pkg.equals(normalizePackage(callString(bean, "getPackageName")))) {
                return bean;
            }
        }
        return null;
    }

    private static Object getAppAddModel(Context context) throws Exception {
        ClassLoader loader = context != null && context.getClassLoader() != null
                ? context.getClassLoader()
                : AutoGameBackground.class.getClassLoader();
        Class<?> modelClass = loader.loadClass("cn.nubia.gamelauncher.model.AppAddModel");
        Method getInstance = modelClass.getMethod("getInstance");
        return getInstance.invoke(null);
    }

    private static Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name)
                        && method.getParameterTypes().length == parameterCount) {
                    return method;
                }
            }
        }
        return null;
    }

    private static Object invokeByNameAndParamCount(Object target, String name,
                                                    int parameterCount, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findMethod(target.getClass(), name, parameterCount);
            if (method == null) {
                Log.w(TAG, "Method not found: " + name);
                return null;
            }
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to call " + name, t);
            return null;
        }
    }

    private static Object invokeWithArg(Object target, String name, Object arg) {
        if (target == null) {
            return null;
        }
        for (Class<?> current = target.getClass(); current != null;
             current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterTypes().length != 1) {
                    continue;
                }
                Class<?> parameter = method.getParameterTypes()[0];
                if (!parameter.isInstance(arg) && parameter != Object.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    return method.invoke(target, arg);
                } catch (Throwable t) {
                    Log.w(TAG, "Unable to call " + name, t);
                    return null;
                }
            }
        }
        Log.w(TAG, "Method not found: " + name + " with " + arg.getClass().getName());
        return null;
    }

    private static String callString(Object target, String name) {
        Object value = invokeByNameAndParamCount(target, name, 0);
        return value instanceof String ? (String) value : null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static void clearCache(Context context) {
        restoreDefaults(context);

        SharedPreferences.Editor editor = prefs(context).edit();
        Set<String> keys = new HashSet<>(prefs(context).getAll().keySet());
        for (String key : keys) {
            if (key.startsWith(URL_PREFIX) || key.startsWith(TIME_PREFIX)
                    || key.startsWith(ORIG_TYPE_PREFIX) || key.startsWith(ORIG_NET_PREFIX)
                    || key.startsWith(ORIG_CROP_PREFIX) || key.startsWith(ORIG_HIGH_PREFIX)
                    || key.startsWith(ORIG_LAST_TIME_PREFIX)
                    || key.startsWith(ORIG_UPDATE_TIME_PREFIX)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /**
     * Puts the atmosphere state captured before our first update back into each
     * game bean, persists it, and forces Game Space to redraw the wallpaper.
     */
    private static void restoreDefaults(Context context) {
        SharedPreferences prefs = prefs(context);
        Object model;
        try {
            model = getAppAddModel(context);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to load Game Space model for restore", t);
            return;
        }
        if (model == null) {
            return;
        }

        // Drive the restore from every game we wrote a Play URL for. The orig_
        // keys are also included so an interrupted update can still be rolled
        // back, and so builds upgraded from an older version (which did not
        // save originals) still reset to the stock default.
        Set<String> packages = new HashSet<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(URL_PREFIX)) {
                packages.add(key.substring(URL_PREFIX.length()));
            } else if (key.startsWith(ORIG_TYPE_PREFIX)) {
                packages.add(key.substring(ORIG_TYPE_PREFIX.length()));
            }
        }

        int restored = 0;
        for (String pkg : packages) {
            Object bean = findBean(model, pkg);
            if (bean != null && restoreBean(context, model, bean, pkg)) {
                restored++;
            }
        }

        if (restored > 0) {
            invokeByNameAndParamCount(model, "notifyRefreshRunnable", 0);
            Log.i(TAG, "Restored " + restored + " backgrounds to default");
        }
    }

    private static boolean restoreBean(Context context, Object model, Object bean, String pkg) {
        try {
            Object atmosphere = atmosphereOf(bean);
            if (atmosphere == null) {
                return false;
            }
            SharedPreferences prefs = prefs(context);
            String type = prefs.getString(ORIG_TYPE_PREFIX + pkg, null);
            String net = prefs.getString(ORIG_NET_PREFIX + pkg, null);
            String crop = prefs.getString(ORIG_CROP_PREFIX + pkg, null);
            String high = prefs.getString(ORIG_HIGH_PREFIX + pkg, null);
            long lastTime = prefs.getLong(ORIG_LAST_TIME_PREFIX + pkg, 0L);
            long updateTime = prefs.getLong(ORIG_UPDATE_TIME_PREFIX + pkg, 0L);

            if (type == null) {
                // Applied by a build that did not capture the original state.
                // Reset to the stock default: no net URL means Game Space falls
                // back to its bundled local wallpaper.
                type = TYPE_NET;
                net = null;
                crop = null;
                high = null;
                lastTime = 0L;
                updateTime = 0L;
            }

            setField(atmosphere, "mType", isEmpty(type) ? TYPE_NET : type);
            setField(atmosphere, "mNetUrl", isEmpty(net) ? null : net);
            setField(atmosphere, "mCropUrl", isEmpty(crop) ? null : crop);
            setField(atmosphere, "mHighLightUrl", isEmpty(high) ? null : high);
            setField(bean, "mLastUpdateUrlTime", lastTime);
            setField(bean, "mUpdateAtmosphereTime", updateTime);

            invokeWithArg(model, "updateAppItemBeanInAppAddDB", bean);
            Log.i(TAG, "Restored default background for " + pkg);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to restore background for " + pkg, t);
            return false;
        }
    }

    /** Saves the bean's current atmosphere state the first time we touch it. */
    private static void saveOriginalState(Context context, Object bean, String pkg) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(ORIG_TYPE_PREFIX + pkg)) {
            return;
        }
        try {
            Object atmosphere = atmosphereOf(bean);
            if (atmosphere == null) {
                return;
            }
            String type = stringField(atmosphere, "mType");
            String net = stringField(atmosphere, "mNetUrl");
            String crop = stringField(atmosphere, "mCropUrl");
            String high = stringField(atmosphere, "mHighLightUrl");
            long lastTime = longField(bean, "mLastUpdateUrlTime");
            long updateTime = longField(bean, "mUpdateAtmosphereTime");

            SharedPreferences.Editor editor = prefs.edit();
            editor.putString(ORIG_TYPE_PREFIX + pkg, type == null ? "" : type);
            editor.putString(ORIG_NET_PREFIX + pkg, net == null ? "" : net);
            editor.putString(ORIG_CROP_PREFIX + pkg, crop == null ? "" : crop);
            editor.putString(ORIG_HIGH_PREFIX + pkg, high == null ? "" : high);
            editor.putLong(ORIG_LAST_TIME_PREFIX + pkg, lastTime);
            editor.putLong(ORIG_UPDATE_TIME_PREFIX + pkg, updateTime);
            editor.apply();
        } catch (Throwable t) {
            Log.w(TAG, "Unable to save original background state for " + pkg, t);
        }
    }

    private static Object atmosphereOf(Object bean) {
        Object atmosphere = invokeByNameAndParamCount(bean, "getAtmosphere", 0);
        return atmosphere != null ? atmosphere : getField(bean, "mAtmosphere");
    }

    private static Object getField(Object target, String name) {
        for (Class<?> current = target.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Keep walking up the hierarchy.
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        for (Class<?> current = target.getClass(); current != null;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // Keep walking up the hierarchy.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static String stringField(Object target, String name) {
        Object value = getField(target, name);
        return value instanceof String ? (String) value : null;
    }

    private static long longField(Object target, String name) {
        Object value = getField(target, name);
        return value instanceof Long ? (Long) value : 0L;
    }

    private static int cachedCount(Context context) {
        int count = 0;
        for (String key : prefs(context).getAll().keySet()) {
            if (key.startsWith(URL_PREFIX)) {
                count++;
            }
        }
        return count;
    }

    private static CharSequence buildSummary(Context context) {
        int count = cachedCount(context);
        if (count == 0) {
            return "Tap to update backgrounds";
        }
        return count + " background" + (count == 1 ? "" : "s") + " cached";
    }

    private static void refreshPreferenceSummary(Context context) {
        Preference preference = sPreference.get();
        if (preference != null) {
            preference.setSummary(buildSummary(context));
        }
    }

    private static void toast(Context context, String message) {
        MAIN_HANDLER.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static String normalizePackage(String value) {
        if (isEmpty(value)) {
            return null;
        }
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c == '/' || c == ',' || c == ';' || c == ' ') {
                length = i;
                break;
            }
        }
        return value.substring(0, length).trim();
    }

    private static String unescapeHtml(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}
