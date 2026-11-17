package app.abeja.extension.gamespace;

import android.util.Log;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Wires the "Patch options" category into Game Center's settings menu. */
public final class PatchOptions {
    private static final String TAG = "PatchOptions";
    private static final String CATEGORY_SIMPLE_NAME = "PatchOptionsFragment";
    private static final String OTHER_OPTIONS = "OtherOptionsFragment";
    private static final String ABOUT = "AboutFragment";

    private PatchOptions() {
    }

    /** Insert our category directly after "Other options" in the left menu. */
    public static void addCategory(ArrayList<?> categories) {
        if (categories == null || find(categories, CATEGORY_SIMPLE_NAME) >= 0) {
            return;
        }
        try {
            int otherIndex = find(categories, OTHER_OPTIONS);
            int insertAt = otherIndex >= 0 ? otherIndex + 1 : find(categories, ABOUT);
            if (insertAt < 0) {
                insertAt = categories.size();
            }

            int icon = 0;
            int title = 0;
            int templateIndex = otherIndex >= 0 ? otherIndex : find(categories, ABOUT);
            if (templateIndex >= 0) {
                Object template = categories.get(templateIndex);
                icon = (Integer) call(template, "getIcon");
                title = (Integer) call(template, "getTitle");
            }

            Class<?> infoClass = Class.forName("cn.nubia.gamecenter.settings.CategoryInfo");
            Object info;
            try {
                // 17.0 has a four-argument constructor with an "isSupport" flag.
                Constructor<?> constructor = infoClass.getConstructor(
                        Class.class, int.class, int.class, boolean.class);
                info = constructor.newInstance(
                        PatchOptionsFragment.class, icon, title, true);
            } catch (NoSuchMethodException ignored) {
                // 16.5 only has the three-argument constructor.
                Constructor<?> constructor = infoClass.getConstructor(
                        Class.class, int.class, int.class);
                info = constructor.newInstance(PatchOptionsFragment.class, icon, title);
            }

            Method add = categories.getClass().getMethod("add", int.class, Object.class);
            add.invoke(categories, insertAt, info);
        } catch (Throwable t) {
            Log.w(TAG, "Unable to add the patch options category", t);
        }
    }

    /**
     * Show our fragment instead of going through Game Center's FragmentInterface
     * cast, which our extension fragment does not implement.
     */
    public static boolean handleSwitch(Class<?> cls, FragmentActivity activity) {
        if (cls != PatchOptionsFragment.class || activity == null) {
            return false;
        }
        try {
            FragmentManager manager = activity.getSupportFragmentManager();
            FragmentTransaction transaction = manager.beginTransaction();

            Fragment current = (Fragment) getField(activity, "mCurrentFragment");
            if (current != null && !(current instanceof PatchOptionsFragment)) {
                transaction.hide(current);
            }

            Fragment target = manager.findFragmentByTag(CATEGORY_SIMPLE_NAME);
            if (target == null) {
                target = new PatchOptionsFragment();
                int container = activity.getResources().getIdentifier(
                        "content", "id", activity.getPackageName());
                transaction.add(container, target, CATEGORY_SIMPLE_NAME);
            } else {
                transaction.show(target);
            }

            setField(activity, "mCurrentFragment", target);
            transaction.commit();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to show the patch options fragment", t);
            return false;
        }
    }

    /** Replace the copied resource title with our own label in the left menu. */
    public static void onBindCategory(Object adapter, Object holder) {
        try {
            Object info = categoryInfo(adapter, holder);
            if (info == null || !CATEGORY_SIMPLE_NAME.equals(call(info, "getSimpleName"))) {
                return;
            }
            Field titleField = holder.getClass().getDeclaredField("mTitleView");
            titleField.setAccessible(true);
            TextView title = (TextView) titleField.get(holder);
            if (title != null) {
                title.setText("Patch options");
            }
        } catch (Throwable t) {
            Log.w(TAG, "Unable to set the patch options title", t);
        }
    }

    private static Object categoryInfo(Object adapter, Object holder) throws Exception {
        Field categoriesField = adapter.getClass().getDeclaredField("mCategories");
        categoriesField.setAccessible(true);
        List<?> categories = (List<?>) categoriesField.get(adapter);
        int position = (Integer) call(holder, "getAdapterPosition");
        if (categories == null || position < 0 || position >= categories.size()) {
            return null;
        }
        return categories.get(position);
    }

    private static int find(List<?> categories, String simpleName) {
        for (int i = 0; i < categories.size(); i++) {
            Object info = categories.get(i);
            if (info != null && simpleName.equals(call(info, "getSimpleName"))) {
                return i;
            }
        }
        return -1;
    }

    private static Object call(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
