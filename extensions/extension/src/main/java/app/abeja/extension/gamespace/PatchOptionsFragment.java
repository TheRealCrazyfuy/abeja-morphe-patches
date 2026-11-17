package app.abeja.extension.gamespace;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/** Settings screen shown by the "Patch options" category in Game Center. */
public class PatchOptionsFragment extends PreferenceFragmentCompat {
    private static final String TAG = "PatchOptionsFragment";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(screen);
        AutoGameBackground.addPreference(screen);
        StartAnimationSettings.addPreference(screen);
        StartAnimationRumble.addPreference(screen);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        styleList(getView());
    }

    /**
     * Game Center fades the outgoing fragment to alpha 0 before switching
     * screens. Stock fragments restore their alpha in onHiddenChanged; we have
     * to do the same or this screen becomes invisible after the first visit.
     */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        View view = getView();
        if (view != null) {
            view.setAlpha(hidden ? 0f : 1f);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        View view = getView();
        if (view != null) {
            view.setAlpha(1f);
        }
    }

    /** Match the padding and divider Game Space uses on its other screens. */
    private void styleList(View view) {
        Context context = getContext();
        if (view == null || context == null) {
            return;
        }
        try {
            int recyclerViewId = context.getResources().getIdentifier(
                    "recycler_view", "id", context.getPackageName());
            if (recyclerViewId == 0) {
                return;
            }
            View list = view.findViewById(recyclerViewId);
            Class<?> recyclerViewClass = Class.forName("androidx.recyclerview.widget.RecyclerView");
            if (list == null || !recyclerViewClass.isInstance(list)) {
                return;
            }

            int paddingId = context.getResources().getIdentifier(
                    "gcs_recyclerview_padding_top", "dimen", context.getPackageName());
            int top = paddingId != 0
                    ? context.getResources().getDimensionPixelSize(paddingId)
                    : Math.round(24 * context.getResources().getDisplayMetrics().density);
            Method setPadding = recyclerViewClass.getMethod(
                    "setPadding", int.class, int.class, int.class, int.class);
            setPadding.invoke(list, 0, top, 0, 0);

            Class<?> decorationClass = Class.forName(
                    "cn.nubia.gamecenter.settings.preference.GameCenterDividerGridItemDecoration");
            Constructor<?> constructor = decorationClass.getConstructor(Context.class);
            Object decoration = constructor.newInstance(context);
            Class<?> itemDecorationClass =
                    Class.forName("androidx.recyclerview.widget.RecyclerView$ItemDecoration");
            Method addItemDecoration = recyclerViewClass.getMethod(
                    "addItemDecoration", itemDecorationClass);
            addItemDecoration.invoke(list, decoration);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to style the patch options list", t);
        }
    }
}
