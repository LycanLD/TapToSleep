package com.lycn.taptosleep.cute;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    static final String TAG = "TapToSleep";
    static final String TARGET_PACKAGE = "com.google.android.apps.nexuslauncher";

    static final String PREFS_FILE = "com.android.launcher3.prefs";
    static final String PREF_KEY = "pref_double_tap_to_sleep";
    static final String PREF_TITLE = "Double tap to sleep";
    static final String PREF_SUMMARY =
            "Turn off the screen by double tapping an empty area of the home screen (>w<)";

    private static final String WORKSPACE_CLASS = "com.android.launcher3.Workspace";
    private static final String SETTINGS_FRAGMENT_CLASS =
            "com.android.launcher3.settings.SettingsActivity$LauncherSettingsFragment";
    private static final String SWITCH_PREFERENCE_CLASS = "androidx.preference.SwitchPreference";

    private static final long DOUBLE_TAP_TIMEOUT_MS = 400;

    private static long sDownTime;
    private static float sDownX;
    private static float sDownY;
    private static boolean sMoved;

    private static boolean sHasPreviousTap;
    private static long sPreviousTapUpTime;
    private static float sPreviousTapX;
    private static float sPreviousTapY;

    private static Boolean sRootAvailable;
    private static Method sModalStateMethod;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        hookWorkspaceTouch(lpparam.classLoader);
        hookSettings(lpparam.classLoader);
        Log.i(TAG, "hooked " + lpparam.packageName);
    }

    // --------------------------------------------------------------------- touch

    private static void hookWorkspaceTouch(final ClassLoader classLoader) {
        try {
            Class<?> workspace = XposedHelpers.findClass(WORKSPACE_CLASS, classLoader);
            XposedHelpers.findAndHookMethod(workspace, "onTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                onWorkspaceTouch((View) param.thisObject,
                                        (MotionEvent) param.args[0]);
                            } catch (Throwable t) {
                                Log.e(TAG, "touch hook failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "could not hook " + WORKSPACE_CLASS, t);
        }
    }

    static void onWorkspaceTouch(View workspace, MotionEvent event) {
        if (workspace == null || event == null || isModal(workspace)) {
            return;
        }

        final int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            sDownTime = event.getEventTime();
            sDownX = event.getX();
            sDownY = event.getY();
            sMoved = false;
            return;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            if (!sMoved) {
                final float slop = ViewConfiguration.get(workspace.getContext())
                        .getScaledTouchSlop();
                final float dx = event.getX() - sDownX;
                final float dy = event.getY() - sDownY;
                if (dx * dx + dy * dy > slop * slop) {
                    sMoved = true;
                }
            }
            return;
        }

        if (action == MotionEvent.ACTION_CANCEL) {
            reset();
            return;
        }

        if (action != MotionEvent.ACTION_UP) {
            return;
        }

        final Context context = workspace.getContext();
        if (sMoved || (event.getEventTime() - sDownTime) > ViewConfiguration.getLongPressTimeout()) {
            reset();
            return;
        }

        final float x = event.getX();
        final float y = event.getY();

        if (sHasPreviousTap
                && (event.getEventTime() - sPreviousTapUpTime) <= DOUBLE_TAP_TIMEOUT_MS
                && withinDoubleTapSlop(context, x, y, sPreviousTapX, sPreviousTapY)) {
            reset();
            if (isEnabled(context)) {
                Log.d(TAG, "double tap detected - sleeping");
                sleep(context);
            }
            return;
        }

        sHasPreviousTap = true;
        sPreviousTapUpTime = event.getEventTime();
        sPreviousTapX = x;
        sPreviousTapY = y;
    }

    private static boolean withinDoubleTapSlop(Context context, float x1, float y1,
                                               float x2, float y2) {
        final float slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2f;
        final float dx = x1 - x2;
        final float dy = y1 - y2;
        return dx * dx + dy * dy <= slop * slop;
    }

    private static void reset() {
        sHasPreviousTap = false;
        sMoved = false;
    }

    private static boolean isModal(View workspace) {
        try {
            if (sModalStateMethod == null) {
                sModalStateMethod = workspace.getClass().getMethod("workspaceInModalState");
            }
            return Boolean.TRUE.equals(sModalStateMethod.invoke(workspace));
        } catch (Throwable t) {
            return false;
        }
    }

    private static void hookSettings(final ClassLoader classLoader) {
        try {
            final Class<?> settingsFragment =
                    XposedHelpers.findClass(SETTINGS_FRAGMENT_CLASS, classLoader);
            Class<?> preferenceFragment =
                    XposedHelpers.findClass("androidx.preference.PreferenceFragmentCompat", classLoader);
            XposedHelpers.findAndHookMethod(preferenceFragment, "onViewCreated",
                    View.class, android.os.Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!settingsFragment.isInstance(param.thisObject)) {
                                return;
                            }
                            try {
                                installPreference(param.thisObject, classLoader);
                            } catch (Throwable t) {
                                Log.e(TAG, "settings hook failed", t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.e(TAG, "could not hook " + SETTINGS_FRAGMENT_CLASS, t);
        }
    }

    static void installPreference(Object fragment, ClassLoader classLoader) {
        final Object screen = XposedHelpers.callMethod(fragment, "getPreferenceScreen");
        if (screen == null) {
            return;
        }
        if (XposedHelpers.callMethod(screen, "findPreference", PREF_KEY) != null) {
            return;
        }

        final Context context = fragmentContext(fragment, screen);
        if (context == null) {
            return;
        }

        final Class<?> switchPreference =
                XposedHelpers.findClass(SWITCH_PREFERENCE_CLASS, classLoader);
        final Object preference =
                XposedHelpers.newInstance(switchPreference, context, (AttributeSet) null);

        XposedHelpers.setObjectField(preference, "mKey", PREF_KEY);
        XposedHelpers.setObjectField(preference, "mTitle", PREF_TITLE);
        XposedHelpers.setObjectField(preference, "mSummary", PREF_SUMMARY);
        XposedHelpers.setObjectField(preference, "mPersistent", Boolean.TRUE);

        XposedHelpers.callMethod(screen, "addPreference", preference);
        Log.i(TAG, "injected \"" + PREF_TITLE + "\" into Home settings");
    }

    private static Context fragmentContext(Object fragment, Object screen) {
        try {
            return (Context) XposedHelpers.callMethod(fragment, "requireContext");
        } catch (Throwable ignored) {
            try {
                return (Context) XposedHelpers.getObjectField(screen, "mContext");
            } catch (Throwable t) {
                return null;
            }
        }
    }

    static boolean isEnabled(Context context) {
        try {
            SharedPreferences prefs = context.getApplicationContext()
                    .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
            return prefs.getBoolean(PREF_KEY, false);
        } catch (Throwable t) {
            return false;
        }
    }

    static void sleep(Context context) {
        try {
            PowerManager powerManager = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                powerManager.goToSleep(SystemClock.uptimeMillis(), 0, 0);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "goToSleep rejected (" + t + "), trying root fallback");
        }
        sleepWithRoot();
    }

    private static synchronized void sleepWithRoot() {
        if (sRootAvailable != null && !sRootAvailable) {
            return;
        }
        final Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                try {
                    Process process = Runtime.getRuntime()
                            .exec(new String[] {"su", "-c", "input keyevent 26"});
                    ok = process.waitFor() == 0;
                } catch (Throwable t) {
                    Log.w(TAG, "root sleep fallback failed", t);
                }
                synchronized (MainHook.class) {
                    sRootAvailable = ok;
                }
            }
        }, "TapToSleep");
        thread.setDaemon(true);
        thread.start();
    }
}
