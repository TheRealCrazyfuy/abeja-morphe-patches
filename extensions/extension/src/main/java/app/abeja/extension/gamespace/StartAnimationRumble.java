package app.abeja.extension.gamespace;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.TwoStatePreference;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Settings switch and hook for disabling the start animation vibration. */
public final class StartAnimationRumble {
    private static final String TAG = "StartAnimationRumble";
    private static final String PREFS_NAME = "game_space_start_animation_rumble";
    private static final String KEY_DISABLE = "disable_start_animation_rumble";
    private static final String KEY_PREFERENCE = "abeja_disable_start_animation_rumble";

    private static boolean sPatchEnabled;

    private StartAnimationRumble() {
    }

    /** Called from the patch so the row only appears when this patch is enabled. */
    public static void markEnabled() {
        sPatchEnabled = true;
    }

    /** Add a Game Center styled switch to the shared Patch options screen. */
    public static void addPreference(PreferenceScreen screen) {
        if (!sPatchEnabled || screen == null) {
            return;
        }

        Context context = screen.getContext();
        if (context == null || screen.findPreference(KEY_PREFERENCE) != null) {
            return;
        }

        try {
            Class<?> clazz = Class.forName(
                    "cn.nubia.gamecenter.settings.preference.GameCenterSwitchPreference");
            Constructor<?> constructor = clazz.getConstructor(Context.class, AttributeSet.class);
            Object instance = constructor.newInstance(context, null);
            if (!(instance instanceof Preference)) {
                return;
            }

            Preference preference = (Preference) instance;
            preference.setKey(KEY_PREFERENCE);
            preference.setTitle("Disable rumble during start animation");
            preference.setPersistent(false);

            boolean disabled = isDisabled(context);
            preference.setDefaultValue(disabled);
            if (instance instanceof TwoStatePreference) {
                ((TwoStatePreference) instance).setChecked(disabled);
            }

            attachChangeListener(instance, context);
            screen.addPreference(preference);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to add the start animation rumble switch", t);
        }
    }

    private static void attachChangeListener(Object preference, Context context) throws Exception {
        Class<?> listenerClass = Class.forName(
                "cn.nubia.gamecenter.settings.compatible.SwitchPreference$OnPreferenceChangeListener");
        Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        switch (method.getName()) {
                            case "hashCode":
                                return System.identityHashCode(proxy);
                            case "equals":
                                return proxy == args[0];
                            case "toString":
                                return "StartAnimationRumbleChangeListener";
                            default:
                                return null;
                        }
                    }
                    if ("onPreferenceChange".equals(method.getName())
                            && args != null && args.length == 2
                            && args[1] instanceof Boolean) {
                        boolean value = (Boolean) args[1];
                        preferences(context).edit().putBoolean(KEY_DISABLE, value).apply();
                        if (preference instanceof TwoStatePreference) {
                            ((TwoStatePreference) preference).setChecked(value);
                        }
                        // The stock switch preference updates the toggle itself.
                        return false;
                    }
                    return null;
                });
        Method setter = preference.getClass().getMethod(
                "setOnPreferenceChangeListener", listenerClass);
        setter.invoke(preference, listener);
    }

    /**
     * Called from the patched {@code Wallpaper.doVibrate()}.
     *
     * @param resourceId the vibration resource id from the original instruction.
     */
    public static void doVibrateIfEnabled(int resourceId) {
        if (isDisabled(getAppContext())) {
            return;
        }
        try {
            Class<?> util = Class.forName("cn.nubia.gamelauncher.util.Util");
            Method vibrate = util.getMethod("doMyOsVibrateOfHe", int.class);
            vibrate.invoke(null, resourceId);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to perform the start animation vibration", t);
        }
    }

    private static boolean isDisabled(Context context) {
        if (context == null) {
            return false;
        }
        return preferences(context).getBoolean(KEY_DISABLE, false);
    }

    private static SharedPreferences preferences(Context context) {
        Context appContext = context.getApplicationContext();
        if (appContext == null) {
            appContext = context;
        }
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static Context getAppContext() {
        try {
            Class<?> application = Class.forName("cn.nubia.gamelauncher.GameLauncherApplication");
            Method method = application.getMethod("getAppContext");
            Object context = method.invoke(null);
            if (context instanceof Context) {
                return (Context) context;
            }
        } catch (Throwable ignored) {
            // Fall through to ActivityThread.
        }

        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getMethod("currentApplication");
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                return (Context) application;
            }
        } catch (Throwable ignored) {
            // No context available.
        }
        return null;
    }
}
