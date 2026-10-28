package com.appupdatehider;

import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LSPosed 模块入口：应用更新隐藏器。
 *
 * 唯一功能：在应用商店/应用市场中隐藏指定应用的“可更新”信息。
 *
 * 作用域：只需勾选应用商店（如 ColorOS 的 com.heytap.market），
 *        不需要勾系统框架，也不拦截任何安装行为。
 *
 * 原理：在商店进程 hook 它的 PackageManager 查询：
 *   - 已安装列表过滤黑名单；单包查询抛 NameNotFoundException（视为未安装）；
 *   - 版本号伪装为最大值，使商店认为“已是最新版本”，不再提示更新。
 *
 * 关键细节（缺一不可）：
 *   - 清单声明 xposedsharedprefs=true，LSPosed 才会把黑名单暴露给商店进程；
 *   - 初始化延迟到 ContextWrapper.attachBaseContext，此时类加载完整、Context 可用；
 *   - 黑名单读取以 XSharedPreferences 为主、ContentProvider 为兜底。
 */
public class AppUpdateHider implements IXposedHookLoadPackage {

    private static final String TAG = "AppUpdateHider";
    private static final String MODULE_PKG = "com.appupdatehider";
    private static final String PREF_NAME = "blocked";
    private static final String KEY_BLOCKED = "blocked_packages";

    private static final AtomicBoolean sInit = new AtomicBoolean(false);
    private static final Set<String> EMPTY = Collections.emptySet();

    private static boolean sLoggedRead = false;
    private static Context sCtx = null;
    private static Set<String> sCache = null;
    private static long sCacheAt = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 不 hook 模块自身
        if (MODULE_PKG.equals(lpparam.packageName)) return;

        // 只处理应用商店等普通应用；系统框架不再需要（本模块不拦截安装）
        if ("android".equals(lpparam.packageName)) return;

        Log.i(TAG, "handleLoadPackage: " + lpparam.packageName);

        // 延迟到 attachBaseContext：类加载完整、Context 可用，hook 最可靠
        try {
            Class<?> cw = XposedHelpers.findClass("android.content.ContextWrapper", lpparam.classLoader);
            XposedBridge.hookAllMethods(cw, "attachBaseContext", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (sInit.compareAndSet(false, true)) {
                        Context ctx = (param.args != null && param.args.length > 0
                                && param.args[0] instanceof Context) ? (Context) param.args[0] : null;
                        init(lpparam, ctx);
                    }
                }
            });
            Log.i(TAG, "attachBaseContext hooked in " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "attachBaseContext hook failed in " + lpparam.packageName, t);
            init(lpparam, null);
        }
    }

    private void init(XC_LoadPackage.LoadPackageParam lpparam, Context ctx) {
        if (ctx != null) sCtx = ctx;
        // 黑名单应用自身进程跳过，避免它查询自己时“以为未安装”而异常
        if (isBlocked(lpparam.packageName)) return;

        Log.i(TAG, "init: " + lpparam.packageName + ", hasCtx=" + (ctx != null));
        try {
            hookAppSpoof(lpparam.classLoader);
        } catch (Throwable t) {
            Log.e(TAG, "hook failed in " + lpparam.packageName, t);
        }
    }

    // ============ 在商店中隐藏更新信息 ============

    private void hookAppSpoof(ClassLoader cl) {
        Class<?> apm;
        try {
            apm = XposedHelpers.findClass("android.app.ApplicationPackageManager", cl);
        } catch (Throwable t) {
            Log.d(TAG, "ApplicationPackageManager not found");
            return;
        }

        // 已安装列表：过滤黑名单（真隐藏）
        XposedBridge.hookAllMethods(apm, "getInstalledPackages", hideListHook(false));
        XposedBridge.hookAllMethods(apm, "getInstalledApplications", hideListHook(false));

        // 单包查询：视为未安装
        XposedBridge.hookAllMethods(apm, "getPackageInfo", notFoundHook());
        XposedBridge.hookAllMethods(apm, "getApplicationInfo", notFoundHook());

        // 启动意图探测
        XposedBridge.hookAllMethods(apm, "getLaunchIntentForPackage", launchIntentHook());

        // intent 组件解析
        for (String m : new String[]{"queryIntentActivities", "queryIntentServices",
                "queryBroadcastReceivers", "queryContentProviders"}) {
            XposedBridge.hookAllMethods(apm, m, hideListHook(true));
        }

        // 版本号伪装兜底：即使商店用别的方式拿到对象，也认为“已是最新”
        try {
            Class<?> pi = XposedHelpers.findClass("android.content.pm.PackageInfo", cl);
            XposedBridge.hookAllMethods(pi, "getLongVersionCode", longVersionHook());
        } catch (Throwable ignored) {
        }

        // 系统商店可能绕过 ApplicationPackageManager，直接走 IPackageManager binder
        try {
            Class<?> proxy = XposedHelpers.findClass("android.content.pm.IPackageManager$Stub$Proxy", cl);
            XposedBridge.hookAllMethods(proxy, "getInstalledPackages", hideSliceHook());
            XposedBridge.hookAllMethods(proxy, "getInstalledApplications", hideSliceHook());
            Log.i(TAG, "IPackageManager proxy hooks installed");
        } catch (Throwable ignored) {
        }

        Log.i(TAG, "Hide hooks installed");
    }

    /** 列表过滤；resolveInfo=true 时按内层 activityInfo/serviceInfo/providerInfo 取包名 */
    private XC_MethodHook hideListHook(final boolean resolveInfo) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Set<String> blocked = getBlockedSet();
                if (blocked.isEmpty()) return;
                Object r = param.getResult();
                if (!(r instanceof List)) return;
                List<?> list = (List<?>) r;
                List<Object> out = filter(list, blocked, resolveInfo);
                if (out.size() != list.size()) param.setResult(out);
            }
        };
    }

    /** ParceledListSlice 过滤（IPackageManager 代理路径） */
    private XC_MethodHook hideSliceHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Set<String> blocked = getBlockedSet();
                if (blocked.isEmpty()) return;
                Object slice = param.getResult();
                if (slice == null) return;
                try {
                    List<?> list = (List<?>) XposedHelpers.callMethod(slice, "getList");
                    if (list == null || list.isEmpty()) return;
                    List<Object> out = filter(list, blocked, false);
                    if (out.size() != list.size()) {
                        param.setResult(XposedHelpers.newInstance(slice.getClass(), out));
                    }
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static List<Object> filter(List<?> list, Set<String> blocked, boolean resolveInfo) {
        List<Object> out = new ArrayList<>(list.size());
        for (Object o : list) {
            String pkg = resolveInfo ? resolvePackageOf(o) : packageOf(o);
            if (pkg != null && blocked.contains(pkg)) continue;
            out.add(o);
        }
        return out;
    }

    private XC_MethodHook notFoundHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof String) {
                    String pkg = (String) param.args[0];
                    if (isBlocked(pkg)) {
                        param.setThrowable(new PackageManager.NameNotFoundException(pkg));
                    }
                }
            }
        };
    }

    private XC_MethodHook launchIntentHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof String
                        && isBlocked((String) param.args[0])) {
                    param.setResult(null);
                }
            }
        };
    }

    /** 版本号伪装：黑名单应用的 getLongVersionCode 返回最大值，商店会认为已是最新 */
    private XC_MethodHook longVersionHook() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    String pkg = (String) XposedHelpers.getObjectField(param.thisObject, "packageName");
                    if (pkg != null && isBlocked(pkg)) param.setResult(Long.MAX_VALUE);
                } catch (Throwable ignored) {
                }
            }
        };
    }

    private static String packageOf(Object o) {
        try {
            return (String) XposedHelpers.getObjectField(o, "packageName");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String resolvePackageOf(Object resolveInfo) {
        try {
            for (String field : new String[]{"activityInfo", "serviceInfo", "providerInfo"}) {
                Object info = XposedHelpers.getObjectField(resolveInfo, field);
                if (info != null) return (String) XposedHelpers.getObjectField(info, "packageName");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ============ 黑名单读取（XSharedPreferences 优先，Provider 兜底） ============

    private boolean isBlocked(String pkg) {
        return pkg != null && getBlockedSet().contains(pkg);
    }

    private Set<String> getBlockedSet() {
        long now = System.currentTimeMillis();
        if (sCache != null && (now - sCacheAt) < 1000L) return sCache;
        Set<String> result = loadBlockedSet();
        sCache = result;
        sCacheAt = now;
        return result;
    }

    private Set<String> loadBlockedSet() {
        try {
            XSharedPreferences prefs = new XSharedPreferences(MODULE_PKG, PREF_NAME);
            prefs.reload();
            Set<String> blocked = prefs.getStringSet(KEY_BLOCKED, EMPTY);
            if (blocked != null && !blocked.isEmpty()) {
                logOnce("XSharedPreferences", blocked.size());
                return blocked;
            }
        } catch (Throwable t) {
            Log.w(TAG, "XSharedPreferences failed: " + t.getMessage());
        }

        Set<String> fromProvider = readFromProvider();
        if (fromProvider != null) {
            logOnce("provider", fromProvider.size());
            return fromProvider;
        }

        logOnce("empty", 0);
        return EMPTY;
    }

    private void logOnce(String source, int size) {
        if (!sLoggedRead) {
            sLoggedRead = true;
            Log.i(TAG, "Block list via " + source + ", size=" + size);
        }
    }

    private Set<String> readFromProvider() {
        try {
            Context ctx = getAnyContext();
            if (ctx == null) return null;
            Cursor c = ctx.getContentResolver().query(BlockedProvider.URI, null, null, null, null);
            if (c == null) return null;
            Set<String> out = new HashSet<>();
            try {
                while (c.moveToNext()) out.add(c.getString(0));
            } finally {
                c.close();
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private Context getAnyContext() {
        if (sCtx != null) return sCtx;
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object app = XposedHelpers.callStaticMethod(at, "currentApplication");
            if (app instanceof Context) return sCtx = (Context) app;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> at = XposedHelpers.findClass("android.app.ActivityThread", null);
            Object thread = XposedHelpers.callStaticMethod(at, "currentActivityThread");
            if (thread != null) {
                Object sys = XposedHelpers.callMethod(thread, "getSystemContext");
                if (sys instanceof Context) return sCtx = (Context) sys;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
