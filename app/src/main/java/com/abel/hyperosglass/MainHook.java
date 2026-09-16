package com.abel.hyperosglass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserHandle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/* JADX INFO: loaded from: classes.dex */
public class MainHook extends XposedModule {
    private volatile SharedPreferences sPrefs;
    private static final Set<String> sinkHooked = new HashSet();
    private static final Set<Class<?>> qsEditHooked = Collections.newSetFromMap(new WeakHashMap());
    private static final AtomicBoolean sHideLockFodFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sHideDismissFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sFocusGlassFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sAodBatterySyncFlag = new AtomicBoolean(true);
    private static volatile boolean sAodDozing = false;
    private static final AtomicBoolean sPinGlassFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sQsEditHideFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sNoFoldHistoryFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sNoNotifLimitFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sKeepNotifFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sHideBtUnlockFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sMuteScreenOnFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sCancelVibrateScreenOnFlag = new AtomicBoolean(true);
    private static final AtomicBoolean sAllowManageAllFlag = new AtomicBoolean(true);
    private static final Set<Object> sBleUnlockToasts = Collections.newSetFromMap(new WeakHashMap());
    private static final ThreadLocal<Boolean> sInBleUnlock = new ThreadLocal<Boolean>() { // from class: com.abel.hyperosglass.MainHook.1
        /* JADX INFO: Access modifiers changed from: protected */
        /* JADX WARN: Can't rename method to resolve collision */
        @Override // java.lang.ThreadLocal
        public Boolean initialValue() {
            return Boolean.FALSE;
        }
    };
    private static volatile boolean sReloadReceiverRegistered = false;
    private static final AtomicBoolean sSyncRunning = new AtomicBoolean(false);
    private static volatile boolean sSyncDirty = false;
    private static volatile int sExpandPillId = 0;
    private static volatile Class<?> sExpandViewClass = null;
    private static volatile Field sFodAuthenField = null;
    private static volatile Field sFodAnimMapField = null;
    private static volatile Field sFodCtxField = null;
    private static volatile Boolean sHideResValid = null;
    private static volatile Field sFlow2Field = null;
    private volatile boolean sSinkEnabled = true;
    private volatile boolean sGlassEnabled = true;
    private volatile boolean sHideLockFod = true;
    private volatile boolean sHideDismissBtn = true;
    private volatile boolean sFocusGlass = true;

    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam moduleLoadedParam) {
        try {
            this.sPrefs = getRemotePreferences(Constants.PREFS);
            LogUtil.attach(this);
            reloadPrefs();
            syncRealPrefsAsync();
            LogUtil.logAlways("==== 模块已加载 v3.34（LibXposed API " + getApiVersion() + "，进程=" + moduleLoadedParam.getProcessName() + "）====");
        } catch (Throwable th) {
            LogUtil.logAlways("onModuleLoaded 异常: " + th);
        }
    }

    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam packageLoadedParam) {
        try {
            String packageName = packageLoadedParam.getPackageName();
            ClassLoader defaultClassLoader = packageLoadedParam.getDefaultClassLoader();
            LogUtil.logAlways("onPackageLoaded: " + packageName);
            reloadPrefs();
            syncRealPrefsAsync();
            registerPrefsReloadReceiver();
            if (Constants.TARGET_PKG.equals(packageName)) {
                installNotificationSinkHooks(defaultClassLoader);
                installPluginClassLoaderHooks(defaultClassLoader);
                installMediaIslandDefense(defaultClassLoader);
                installExpandButtonColor(defaultClassLoader);
                installLockFodHooks(defaultClassLoader);
                installHideDismissButtonHook(defaultClassLoader);
                installFocusGlassHooks(defaultClassLoader);
                installAodBatteryHooks(defaultClassLoader);
                installPinGlassHook(defaultClassLoader);
                installQsEditHideHook(defaultClassLoader);
                installNotifEnhanceHooks(defaultClassLoader);
                installThirdPartyThemeGlassHooks(defaultClassLoader);
            }
        } catch (Throwable th) {
            LogUtil.logAlways("onPackageLoaded 异常: " + th);
        }
    }

    private void registerPrefsReloadReceiver() {
        if (sReloadReceiverRegistered) {
            return;
        }
        try {
            Object objCurrentApplication = currentApplication();
            if (objCurrentApplication instanceof Context) {
                ((Context) objCurrentApplication).registerReceiver(new BroadcastReceiver() { // from class: com.abel.hyperosglass.MainHook.2
                    @Override // android.content.BroadcastReceiver
                    public void onReceive(Context context, Intent intent) {
                        try {
                            MainHook.this.reloadPrefs();
                            LogUtil.logAlways("收到重载广播，设置已刷新");
                        } catch (Throwable unused) {
                        }
                    }
                }, new IntentFilter(Constants.ACTION_RELOAD_PREFS), 2);
                sReloadReceiverRegistered = true;
                LogUtil.logAlways("[重载] 已注册实时重载广播接收器");
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[重载] 注册失败: " + th);
        }
    }

    private void installPinGlassHook(final ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.PIN_VIEW_CLASS, false, classLoader).getDeclaredMethod("onFinishInflate", new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("pin-glass").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda3
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.this.lambda$installPinGlassHook$1(classLoader, chain);
                }
            });
            LogUtil.logAlways("[密码玻璃] 已挂钩 com.android.keyguard.KeyguardPINView.onFinishInflate");
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 挂钩失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ Object lambda$installPinGlassHook$1(final ClassLoader classLoader, XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        if (sPinGlassFlag.get() && (chain.getThisObject() instanceof View)) {
            final View view = (View) chain.getThisObject();
            view.post(new Runnable() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda7
                @Override // java.lang.Runnable
                public final void run() {
                    MainHook.this.lambda$installPinGlassHook$0(view, classLoader);
                }
            });
        }
        return objProceed;
    }

    private void installNotifEnhanceHooks(ClassLoader classLoader) {
        installNoNotifLimitHook(classLoader);
        installKeepNotifHook(classLoader);
        installHideBtUnlockHook(classLoader);
        installMuteScreenOnHook(classLoader);
        installAllowManageAllHook(classLoader);
        installFoldNotifHook(classLoader);
    }

    private static Field findField(Class<?> cls, String str) {
        while (cls != null) {
            try {
                Field declaredField = cls.getDeclaredField(str);
                declaredField.setAccessible(true);
                return declaredField;
            } catch (Throwable unused) {
                cls = cls.getSuperclass();
            }
        }
        return null;
    }

    private static Object entrySbn(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Field fieldFindField = findField(obj.getClass(), Constants.ENTRY_SBN_FIELD);
            if (fieldFindField == null) {
                return null;
            }
            return fieldFindField.get(obj);
        } catch (Throwable unused) {
            return null;
        }
    }

    private void installNoNotifLimitHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.NOTIF_LIMIT_CLASS, false, classLoader).getDeclaredMethod("attach", Class.forName(Constants.NOTIF_PIPELINE_CLASS, false, classLoader));
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("no-notif-limit").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda4
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installNoNotifLimitHook$2(chain);
                }
            });
            LogUtil.logAlways("[解除通知限制] 已挂钩 com.android.systemui.statusbar.notification.collection.coordinator.CountLimitCoordinator.attach");
        } catch (Throwable th) {
            LogUtil.logAlways("[解除通知限制] 挂载失败: " + th);
        }
        try {
            Method declaredMethod2 = Class.forName(Constants.NOTIF_LIMIT_LAMBDA_CLASS, false, classLoader).getDeclaredMethod(Constants.NOTIF_LIMIT_ONVIEWBOUND_METHOD, Class.forName(Constants.NOTIF_ENTRY_CLASS, false, classLoader));
            declaredMethod2.setAccessible(true);
            hook(declaredMethod2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("no-notif-limit-lambda").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda5
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installNoNotifLimitHook$3(chain);
                }
            });
            LogUtil.logAlways("[解除通知限制] 已挂钩提示条 lambda");
        } catch (Throwable th2) {
            LogUtil.logAlways("[解除通知限制] 提示条 lambda 未命中（ROM 差异，可忽略）: " + th2);
        }
    }

    static /* synthetic */ Object lambda$installNoNotifLimitHook$2(XposedInterface.Chain chain) throws Throwable {
        if (sNoNotifLimitFlag.get()) {
            return null;
        }
        return chain.proceed();
    }

    static /* synthetic */ Object lambda$installNoNotifLimitHook$3(XposedInterface.Chain chain) throws Throwable {
        if (sNoNotifLimitFlag.get()) {
            return null;
        }
        return chain.proceed();
    }

    private void installKeepNotifHook(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.KEEP_NOTIF_CLASS, false, classLoader);
            Class<?> cls2 = Class.forName(Constants.NOTIF_ENTRY_CLASS, false, classLoader);
            int i = 0;
            for (Method method : cls.getDeclaredMethods()) {
                if (Constants.KEEP_NOTIF_METHOD.equals(method.getName()) && method.getParameterCount() == 1 && method.getParameterTypes()[0] == cls2) {
                    method.setAccessible(true);
                    hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("keep-notif").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda15
                        public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                            return MainHook.lambda$installKeepNotifHook$4(chain);
                        }
                    });
                    i++;
                }
            }
            LogUtil.logAlways("[解锁保留通知] 已挂钩 shouldHideNotification ×" + i);
        } catch (Throwable th) {
            LogUtil.logAlways("[解锁保留通知] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installKeepNotifHook$4(XposedInterface.Chain chain) throws Throwable {
        if (sKeepNotifFlag.get()) {
            forceNotShownAfterUnlock(chain.getArg(0));
        }
        return chain.proceed();
    }

    private static Object entryNotification(Object obj) {
        Field fieldFindField;
        try {
            Object objEntrySbn = entrySbn(obj);
            if (objEntrySbn == null || (fieldFindField = findField(objEntrySbn.getClass(), "mNotification")) == null) {
                return null;
            }
            return fieldFindField.get(objEntrySbn);
        } catch (Throwable unused) {
            return null;
        }
    }

    private static void forceNotShownAfterUnlock(Object obj) {
        Field fieldFindField;
        try {
            Object objEntrySbn = entrySbn(obj);
            if (objEntrySbn == null || (fieldFindField = findField(objEntrySbn.getClass(), Constants.SBN_SHOWN_AFTER_UNLOCK_FIELD)) == null) {
                return;
            }
            fieldFindField.setBoolean(objEntrySbn, false);
        } catch (Throwable unused) {
        }
    }

    private void installHideBtUnlockHook(ClassLoader classLoader) {
        installBleUnlockSourceHook(classLoader);
        installBleUnlockViewHide();
        installBleUnlockToastHide();
    }

    private void installBleUnlockSourceHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.BLE_UNLOCK_HELPER_CLASS, false, classLoader).getDeclaredMethod(Constants.BLE_TRY_UNLOCK_METHOD, new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-unlock-source").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda14
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installBleUnlockSourceHook$5(chain);
                }
            });
            LogUtil.logAlways("[蓝牙解锁提示] 已挂钩 com.android.keyguard.MiuiBleUnlockHelper.tryUnlockByBle");
        } catch (Throwable th) {
            LogUtil.logAlways("[蓝牙解锁提示] tryUnlockByBle 未命中: " + th);
        }
    }

    static /* synthetic */ Object lambda$installBleUnlockSourceHook$5(XposedInterface.Chain chain) throws Throwable {
        sInBleUnlock.set(Boolean.TRUE);
        try {
            return chain.proceed();
        } finally {
            sInBleUnlock.set(Boolean.FALSE);
        }
    }

    private void installBleUnlockViewHide() {
        try {
            int i = 0;
            for (Method method : TextView.class.getDeclaredMethods()) {
                if ("setText".equals(method.getName())) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length != 0 && parameterTypes[0] == CharSequence.class) {
                        method.setAccessible(true);
                        hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-unlock-text").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda18
                            public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                                return MainHook.this.lambda$installBleUnlockViewHide$6(chain);
                            }
                        });
                        i++;
                    }
                }
            }
            LogUtil.logAlways("[蓝牙解锁提示] 已挂钩 TextView.setText ×" + i);
        } catch (Throwable th) {
            LogUtil.logAlways("[蓝牙解锁提示] 挂载失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ Object lambda$installBleUnlockViewHide$6(XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        try {
            if (sHideBtUnlockFlag.get()) {
                Object thisObject = chain.getThisObject();
                if ((thisObject instanceof View) && isBluetoothUnlockText((CharSequence) chain.getArg(0))) {
                    final View view = (View) thisObject;
                    view.setVisibility(8);
                    view.post(new Runnable() { // from class: com.abel.hyperosglass.MainHook.3
                        @Override // java.lang.Runnable
                        public void run() {
                            try {
                                view.setVisibility(8);
                            } catch (Throwable unused) {
                            }
                        }
                    });
                }
            }
        } catch (Throwable unused) {
        }
        return objProceed;
    }

    private void installBleUnlockToastHide() {
        try {
            Method declaredMethod = Toast.class.getDeclaredMethod("makeText", Context.class, Integer.TYPE, Integer.TYPE);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-toast-make").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda12
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installBleUnlockToastHide$7(chain);
                }
            });
            Method declaredMethod2 = Toast.class.getDeclaredMethod("show", new Class[0]);
            declaredMethod2.setAccessible(true);
            hook(declaredMethod2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("ble-toast-show").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda13
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installBleUnlockToastHide$8(chain);
                }
            });
            LogUtil.logAlways("[蓝牙解锁Toast] 已挂钩 Toast.makeText/show");
        } catch (Throwable th) {
            LogUtil.logAlways("[蓝牙解锁Toast] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installBleUnlockToastHide$7(XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        try {
            if (sHideBtUnlockFlag.get() && objProceed != null && isBleUnlockRes(chain.getArg(0), ((Integer) chain.getArg(1)).intValue())) {
                sBleUnlockToasts.add(objProceed);
            }
        } catch (Throwable unused) {
        }
        return objProceed;
    }

    static /* synthetic */ Object lambda$installBleUnlockToastHide$8(XposedInterface.Chain chain) throws Throwable {
        try {
            if (sHideBtUnlockFlag.get()) {
                if (sInBleUnlock.get().booleanValue()) {
                    return null;
                }
                Object thisObject = chain.getThisObject();
                if (sBleUnlockToasts.remove(thisObject)) {
                    return null;
                }
                if ((thisObject instanceof Toast) && isBluetoothUnlockText(toastText(((Toast) thisObject).getView()))) {
                    return null;
                }
            }
        } catch (Throwable unused) {
        }
        return chain.proceed();
    }

    private static boolean isBleUnlockRes(Object obj, int i) {
        try {
            if (!(obj instanceof Context)) {
                return false;
            }
            Resources resources = ((Context) obj).getResources();
            if (Constants.BLE_UNLOCK_RES_ENTRY.equals(resources.getResourceEntryName(i))) {
                return true;
            }
            return matchBluetoothUnlock(String.valueOf(resources.getText(i)).toLowerCase());
        } catch (Throwable unused) {
            return false;
        }
    }

    private static boolean isBluetoothUnlockText(CharSequence charSequence) {
        if (charSequence == null) {
            return false;
        }
        return matchBluetoothUnlock(charSequence.toString().toLowerCase());
    }

    private static boolean matchBluetoothUnlock(String str) {
        if (str != null && str.length() != 0) {
            for (String str2 : Constants.BLE_UNLOCK_TEXT_BT) {
                if (str.contains(str2)) {
                    for (String str3 : Constants.BLE_UNLOCK_TEXT_UNLOCK) {
                        if (str.contains(str3)) {
                            return true;
                        }
                    }
                    return false;
                }
            }
        }
        return false;
    }

    private static String toastText(View view) {
        StringBuilder sb = new StringBuilder();
        collectText(view, sb);
        return sb.toString();
    }

    private static void collectText(View view, StringBuilder sb) {
        if (view == null) {
            return;
        }
        try {
            if (view instanceof TextView) {
                sb.append(((TextView) view).getText()).append(' ');
            }
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    collectText(viewGroup.getChildAt(i), sb);
                }
            }
        } catch (Throwable unused) {
        }
    }

    private void installMuteScreenOnHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.MUTE_ALERT_CLASS, false, classLoader).getDeclaredMethod(Constants.MUTE_ALERT_METHOD, Class.forName(Constants.NOTIF_ENTRY_CLASS, false, classLoader));
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("mute-screen-on").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda17
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installMuteScreenOnHook$9(chain);
                }
            });
            LogUtil.logAlways("[亮屏静音] 已挂钩 com.android.systemui.statusbar.notification.policy.MiuiAlertManager.buzzBeepBlink");
        } catch (Throwable th) {
            LogUtil.logAlways("[亮屏静音] 挂载失败: " + th);
        }
    }

    /* JADX WARN: Code duplicated, block: B:35:0x006d  */
    /* JADX WARN: Code duplicated, block: B:38:0x0073  */
    /* JADX WARN: Code duplicated, block: B:41:0x0078  */
    /* JADX WARN: Code duplicated, block: B:64:0x0065 A[EXC_TOP_SPLITTER, SYNTHETIC] */
    static /* synthetic */ Object lambda$installMuteScreenOnHook$9(XposedInterface.Chain chain) throws Throwable {
        boolean z;
        int i;
        if (!isScreenInteractive(chain.getThisObject())) {
            return chain.proceed();
        }
        Object obj = null;
        if (sMuteScreenOnFlag.get()) {
            return null;
        }
        if (sCancelVibrateScreenOnFlag.get()) {
            int i2 = 0;
            Object arg = chain.getArg(0);
            Object objEntryNotification = arg == null ? null : entryNotification(arg);
            Field fieldFindField = objEntryNotification == null ? null : findField(objEntryNotification.getClass(), "vibrate");
            Field fieldFindField2 = objEntryNotification == null ? null : findField(objEntryNotification.getClass(), "defaults");
            int i3 = 1;
            if (fieldFindField != null) {
                try {
                    Object obj2 = fieldFindField.get(objEntryNotification);
                    if (obj2 != null) {
                        try {
                            fieldFindField.set(objEntryNotification, null);
                            obj = obj2;
                            z = true;
                            if (fieldFindField2 != null) {
                                try {
                                    i = fieldFindField2.getInt(objEntryNotification);
                                    if ((i & 2) != 0) {
                                        fieldFindField2.setInt(objEntryNotification, i & (-3));
                                    } else {
                                        i3 = 0;
                                    }
                                    i2 = i;
                                } catch (Throwable unused) {
                                    i = 0;
                                }
                            } else {
                                i3 = 0;
                            }
                            i = i2;
                            i2 = i3;
                        } catch (Throwable unused2) {
                            i = 0;
                            obj = obj2;
                            z = false;
                        }
                    } else {
                        obj = obj2;
                        z = false;
                        if (fieldFindField2 != null) {
                            i = fieldFindField2.getInt(objEntryNotification);
                            if ((i & 2) != 0) {
                                fieldFindField2.setInt(objEntryNotification, i & (-3));
                            } else {
                                i3 = 0;
                            }
                            i2 = i;
                        } else {
                            i3 = 0;
                        }
                        i = i2;
                        i2 = i3;
                    }
                } catch (Throwable unused3) {
                    z = false;
                    i = 0;
                }
            } else {
                z = false;
                if (fieldFindField2 != null) {
                    i = fieldFindField2.getInt(objEntryNotification);
                    if ((i & 2) != 0) {
                        fieldFindField2.setInt(objEntryNotification, i & (-3));
                    } else {
                        i3 = 0;
                    }
                    i2 = i;
                } else {
                    i3 = 0;
                }
                i = i2;
                i2 = i3;
            }
            try {
                Object objProceed = chain.proceed();
                if (z && fieldFindField != null) {
                    try {
                        fieldFindField.set(objEntryNotification, obj);
                        if (i2 != 0) {
                        }
                    } catch (Throwable unused4) {
                    }
                } else if (i2 != 0 && fieldFindField2 != null) {
                }
                return objProceed;
            } finally {
                if (z && fieldFindField != null) {
                    try {
                        fieldFindField.set(objEntryNotification, obj);
                        if (i2 != 0) {
                            fieldFindField2.setInt(objEntryNotification, i);
                        }
                    } catch (Throwable unused5) {
                    }
                } else if (i2 != 0 && fieldFindField2 != null) {
                    fieldFindField2.setInt(objEntryNotification, i);
                }
            }
        }
        return chain.proceed();
    }

    private static boolean isScreenInteractive(Object obj) {
        PowerManager powerManager;
        try {
            Field fieldFindField = findField(obj.getClass(), Constants.MUTE_ALERT_CONTEXT_FIELD);
            if (fieldFindField == null) {
                return true;
            }
            Object obj2 = fieldFindField.get(obj);
            if ((obj2 instanceof Context) && (powerManager = (PowerManager) ((Context) obj2).getSystemService("power")) != null) {
                return powerManager.isInteractive();
            }
        } catch (Throwable unused) {
        }
        return true;
    }

    private void installAllowManageAllHook(ClassLoader classLoader) {
        try {
            final Class<?> cls = Class.forName(Constants.NOTIF_CHANNEL_CLASS, false, classLoader);
            Method declaredMethod = cls.getDeclaredMethod(Constants.CHANNEL_IS_BLOCKABLE_METHOD, new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("allow-manage-all-is").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda8
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAllowManageAllHook$10(chain);
                }
            });
            Method declaredMethod2 = cls.getDeclaredMethod(Constants.CHANNEL_SET_BLOCKABLE_METHOD, Boolean.TYPE);
            declaredMethod2.setAccessible(true);
            hook(declaredMethod2).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("allow-manage-all-set").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda9
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAllowManageAllHook$11(cls, chain);
                }
            });
            LogUtil.logAlways("[允许管理所有通知] 已挂钩 NotificationChannel.is/setBlockable");
        } catch (Throwable th) {
            LogUtil.logAlways("[允许管理所有通知] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAllowManageAllHook$10(XposedInterface.Chain chain) throws Throwable {
        return sAllowManageAllFlag.get() ? Boolean.TRUE : chain.proceed();
    }

    static /* synthetic */ Object lambda$installAllowManageAllHook$11(Class cls, XposedInterface.Chain chain) throws Throwable {
        Object objProceed = chain.proceed();
        try {
            if (sAllowManageAllFlag.get()) {
                Field declaredField = cls.getDeclaredField(Constants.CHANNEL_BLOCKABLE_FIELD);
                declaredField.setAccessible(true);
                declaredField.setBoolean(chain.getThisObject(), true);
            }
        } catch (Throwable unused) {
        }
        return objProceed;
    }

    private void installFoldNotifHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.FOLD_NOTIF_CONTROLLER_CLASS, false, classLoader).getDeclaredMethod(Constants.FOLD_SEND_METHOD, UserHandle.class, Class.forName(Constants.FOLD_NOTIF_REASON_CLASS, false, classLoader));
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("no-fold-history").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda16
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installFoldNotifHook$12(chain);
                }
            });
            LogUtil.logAlways("[禁止折叠历史] 已挂钩 com.android.systemui.statusbar.notification.history.FoldNotifControllerImpl.sendFoldNotification");
        } catch (Throwable th) {
            LogUtil.logAlways("[禁止折叠历史] 挂载失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installFoldNotifHook$12(XposedInterface.Chain chain) throws Throwable {
        if (sNoFoldHistoryFlag.get()) {
            return null;
        }
        return chain.proceed();
    }

    private void installQsEditHideHook(ClassLoader classLoader) {
        tryHookQsEditIn(classLoader);
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void tryHookQsEditIn(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        // 插件 loader 出现时，补挂三方主题玻璃的插件侧 guard（MiBlurCompat / 主题控制器 / ThemeUtils）
        installThirdPartyThemeGlassHooks(classLoader);
        try {
            final Class<?> cls = Class.forName(Constants.QS_EDIT_CONTROLLER_CLASS, false, classLoader);
            if (cls == null) {
                return;
            }
            Set<Class<?>> set = qsEditHooked;
            synchronized (set) {
                if (set.contains(cls)) {
                    return;
                }
                set.add(cls);
                Method declaredMethod = cls.getDeclaredMethod("onBindViewHolder", new Class[0]);
                declaredMethod.setAccessible(true);
                hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("qs-edit-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda6
                    public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                        return MainHook.lambda$tryHookQsEditIn$13(cls, chain);
                    }
                });
                LogUtil.logAlways("[控制中心编辑] 已挂钩 EditButtonController.onBindViewHolder（插件 loader）");
            }
        } catch (Throwable th) {
            if (th instanceof ClassNotFoundException) {
                return;
            }
            LogUtil.logAlways("[控制中心编辑] 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$tryHookQsEditIn$13(Class cls, XposedInterface.Chain chain) throws Throwable {
        chain.proceed();
        if (!sQsEditHideFlag.get()) {
            return null;
        }
        try {
            Object thisObject = chain.getThisObject();
            Method declaredMethod = cls.getDeclaredMethod("getEditButton", new Class[0]);
            declaredMethod.setAccessible(true);
            View view = (View) declaredMethod.invoke(thisObject, new Object[0]);
            if (view != null && view.getAlpha() != 0.0f) {
                view.setAlpha(0.0f);
                LogUtil.logAlwaysOnce("qs-edit-hide", "[控制中心编辑] 已对「编辑」按钮设 alpha(0f)（完全透明但保留点击）");
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[控制中心编辑] 命中处理异常: " + th);
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: applyPinGlass, reason: merged with bridge method [inline-methods] */
    public void lambda$installPinGlassHook$0(View view, final ClassLoader classLoader) {
        try {
            ArrayList<View> arrayList = new ArrayList<>(Constants.PIN_KEY_IDS.length);
            collectPinKeys(view, arrayList);
            for (final View view2 : arrayList) {
                view2.post(new Runnable() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda11
                    @Override // java.lang.Runnable
                    public final void run() {
                        MainHook.this.lambda$applyPinGlass$14(view2, classLoader);
                    }
                });
            }
            LogUtil.logAlways("[密码玻璃] 数字键匹配 " + arrayList.size() + "/" + Constants.PIN_KEY_IDS.length);
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 应用失败: " + th);
        }
    }

    private void collectPinKeys(View view, List<View> list) {
        String strResName = resName(view);
        if (strResName != null && Arrays.asList(Constants.PIN_KEY_IDS).contains(strResName)) {
            list.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                collectPinKeys(viewGroup.getChildAt(i), list);
            }
        }
    }

    private String resName(View view) {
        try {
            int id = view.getId();
            if (id == -1 || id == -1) {
                return null;
            }
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable unused) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    /* JADX INFO: renamed from: applyPinKeyMaterial, reason: merged with bridge method [inline-methods] */
    public void lambda$applyPinGlass$14(final View view, ClassLoader classLoader) {
        try {
            if (view instanceof ViewGroup) {
                ViewGroup viewGroup = (ViewGroup) view;
                int iMin = Math.min(view.getWidth(), view.getHeight());
                if (iMin <= 0) {
                    return;
                }
                for (int childCount = viewGroup.getChildCount() - 1; childCount >= 0; childCount--) {
                    if (Constants.PIN_MATERIAL_TAG.equals(viewGroup.getChildAt(childCount).getTag())) {
                        viewGroup.removeViewAt(childCount);
                    }
                }
                final ImageView imageView = new ImageView(view.getContext());
                imageView.setTag(Constants.PIN_MATERIAL_TAG);
                imageView.setClickable(false);
                imageView.setFocusable(false);
                imageView.setImportantForAccessibility(2);
                GradientDrawable gradientDrawable = new GradientDrawable();
                gradientDrawable.setColor(Color.argb(1, 255, 255, 255));
                imageView.setImageDrawable(gradientDrawable);
                GradientDrawable gradientDrawable2 = new GradientDrawable();
                gradientDrawable2.setShape(1);
                gradientDrawable2.setColor(-1);
                imageView.setForeground(new RippleDrawable(ColorStateList.valueOf(1090519039), null, gradientDrawable2));
                imageView.setClipToOutline(true);
                imageView.setOutlineProvider(new ViewOutlineProvider() { // from class: com.abel.hyperosglass.MainHook.4
                    @Override // android.view.ViewOutlineProvider
                    public void getOutline(View view2, Outline outline) {
                        outline.setOval(0, 0, view2.getWidth(), view2.getHeight());
                    }
                });
                viewGroup.addView(imageView, 0, new ViewGroup.LayoutParams(iMin, iMin));
                view.setBackground(null);
                placePinMaterial(view, imageView);
                view.addOnLayoutChangeListener(new View.OnLayoutChangeListener() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda1
                    @Override // android.view.View.OnLayoutChangeListener
                    public final void onLayoutChange(View view2, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
                        MainHook.this.lambda$applyPinKeyMaterial$15(view, imageView, view2, i, i2, i3, i4, i5, i6, i7, i8);
                    }
                });
                view.setOnTouchListener(new View.OnTouchListener() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda2
                    @Override // android.view.View.OnTouchListener
                    public final boolean onTouch(View view2, MotionEvent motionEvent) {
                        return MainHook.lambda$applyPinKeyMaterial$16(imageView, view2, motionEvent);
                    }
                });
                configurePinLabel(viewGroup);
                applyPinBackdropMaterial(imageView, 14, 80, -1);
                applyPinGlassMaterial(imageView, classLoader, 36, 0.14f);
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 单键材质失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public /* synthetic */ void lambda$applyPinKeyMaterial$15(View view, ImageView imageView, View view2, int i, int i2, int i3, int i4, int i5, int i6, int i7, int i8) {
        placePinMaterial(view, imageView);
    }

    static /* synthetic */ boolean lambda$applyPinKeyMaterial$16(ImageView imageView, View view, MotionEvent motionEvent) {
        imageView.setPressed(motionEvent.getActionMasked() == 0 || motionEvent.getActionMasked() == 2);
        return false;
    }

    private void placePinMaterial(View view, View view2) {
        int iMin = Math.min(view.getWidth(), view.getHeight());
        if (iMin <= 0) {
            return;
        }
        ViewGroup.LayoutParams layoutParams = view2.getLayoutParams();
        layoutParams.width = iMin;
        layoutParams.height = iMin;
        view2.setLayoutParams(layoutParams);
        int width = (view.getWidth() - iMin) / 2;
        int height = (view.getHeight() - iMin) / 2;
        view2.layout(width, height, width + iMin, iMin + height);
        view2.invalidateOutline();
    }

    private void configurePinLabel(View view) {
        if ((view instanceof TextView) && Constants.PIN_LABEL_ID.equals(resName(view))) {
            TextView textView = (TextView) view;
            textView.setEllipsize(null);
            textView.setSingleLine(false);
            textView.setMaxLines(1);
            textView.setHorizontallyScrolling(false);
            textView.setTextScaleX(0.86f);
            textView.setIncludeFontPadding(false);
        }
        if (view instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) view;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                configurePinLabel(viewGroup.getChildAt(i));
            }
        }
    }

    private void applyPinBackdropMaterial(View view, int i, int i2, int i3) {
        try {
            View.class.getMethod("clearMiBackgroundBlendColor", new Class[0]).invoke(view, new Object[0]);
            View.class.getMethod("setPassWindowBlurEnabled", Boolean.TYPE).invoke(view, true);
            View.class.getMethod("setMiViewBlurMode", Integer.TYPE).invoke(view, 1);
            View.class.getMethod("setMiBackgroundBlurMode", Integer.TYPE).invoke(view, 1);
            View.class.getMethod("setMiBackgroundBlurRadius", Integer.TYPE).invoke(view, Integer.valueOf(Math.min(i2, Constants.PIN_MAX_BACKDROP_BLUR_RADIUS)));
            View.class.getMethod("addMiBackgroundBlendColor", Integer.TYPE, Integer.TYPE).invoke(view, Integer.valueOf(Color.argb((i * 255) / 100, Color.red(i3), Color.green(i3), Color.blue(i3))), Integer.valueOf(Constants.PIN_GLASS_BLEND_MODE));
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] backdrop 材质失败: " + th);
        }
    }

    private void applyPinGlassMaterial(View view, ClassLoader classLoader, int i, float f) {
        try {
            Class<?> cls = Class.forName(Constants.MI_GLASS_COMPAT_CLASS, false, classLoader);
            int iMax = Math.max(0, Math.min(i, 100));
            float[] fArr = (float[]) Constants.PIN_GLASS_PARAMS.clone();
            fArr[4] = Math.max(0.0f, Math.min(f, 0.4f));
            cls.getMethod("setMiGlassBlurRadius", View.class, Integer.TYPE, Integer.TYPE).invoke(null, view, Integer.valueOf(iMax), Integer.valueOf(Math.min(iMax * 2, Constants.PIN_MAX_LARGE_BLUR_RADIUS)));
            cls.getMethod("setMiViewMaterialTypeCompat", Integer.TYPE, View.class).invoke(null, 1, view);
            cls.getMethod("setMiGlassCompat", View.class, float[].class).invoke(null, view, fArr);
        } catch (Throwable th) {
            LogUtil.logAlways("[密码玻璃] 柔光材质失败: " + th);
        }
    }

    public void onHotReloaded(XposedModuleInterface.HotReloadedParam hotReloadedParam) {
        try {
            reloadPrefs();
            LogUtil.logAlways("热重载完成，设置已刷新");
        } catch (Throwable unused) {
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public void reloadPrefs() {
        if (this.sPrefs == null) {
            return;
        }
        try {
            Bundle bundle = new Bundle();
            for (int i = 0; i < Constants.ALL_PREF_KEYS.length; i++) {
                bundle.putBoolean(Constants.ALL_PREF_KEYS[i], this.sPrefs.getBoolean(Constants.ALL_PREF_KEYS[i], Constants.ALL_PREF_DEFAULTS[i]));
            }
            applyRealPrefs(bundle);
        } catch (Throwable th) {
            LogUtil.logAlways("读取设置失败: " + th);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public boolean trySyncRealPrefsOnce() {
        Bundle bundleCall;
        try {
            Object objCurrentApplication = currentApplication();
            if ((objCurrentApplication instanceof Context) && (bundleCall = ((Context) objCurrentApplication).getContentResolver().call(Uri.parse(Constants.STATUS_URI), Constants.METHOD_GET_PREFS, (String) null, (Bundle) null)) != null && bundleCall.getBoolean("ok", false)) {
                applyRealPrefs(bundleCall);
                return true;
            }
        } catch (Throwable unused) {
        }
        return false;
    }

    private void syncRealPrefsAsync() {
        if (!sSyncRunning.compareAndSet(false, true)) {
            sSyncDirty = true;
            return;
        }
        Thread thread = new Thread(new Runnable() { // from class: com.abel.hyperosglass.MainHook.5
            @Override // java.lang.Runnable
            public void run() {
                do {
                    try {
                        MainHook.sSyncDirty = false;
                        int i = 0;
                        while (true) {
                            if (i < 40) {
                                if (MainHook.this.trySyncRealPrefsOnce()) {
                                    break;
                                }
                                try {
                                    Thread.sleep(800L);
                                } catch (Throwable unused) {
                                }
                                i++;
                            } else {
                                LogUtil.logAlways("设置(真实值同步) 模块 App 不可达，本轮放弃（最多 40 次）");
                                break;
                            }
                        }
                    } catch (Throwable th) {
                        MainHook.sSyncRunning.set(false);
                        throw th;
                    }
                } while (MainHook.sSyncDirty);
                MainHook.sSyncRunning.set(false);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    private void applyRealPrefs(Bundle bundle) {
        try {
            boolean z = bundle.getBoolean(Constants.PREFS_SINK_ENABLED, true);
            boolean z2 = bundle.getBoolean(Constants.PREFS_GLASS_ENABLED, true);
            boolean z3 = bundle.getBoolean(Constants.PREFS_HIDE_LOCK_FOD, true);
            boolean z4 = bundle.getBoolean(Constants.PREFS_HIDE_DISMISS_BTN, true);
            boolean z5 = bundle.getBoolean(Constants.PREFS_FOCUS_GLASS, true);
            boolean z6 = bundle.getBoolean(Constants.PREFS_AOD_BATTERY_SYNC, true);
            boolean z7 = bundle.getBoolean(Constants.PREFS_PIN_GLASS, true);
            boolean z8 = bundle.getBoolean(Constants.PREFS_QS_EDIT_HIDE, true);
            boolean z9 = bundle.getBoolean(Constants.PREFS_NO_FOLD_HISTORY, true);
            boolean z10 = bundle.getBoolean(Constants.PREFS_NO_NOTIF_LIMIT, true);
            boolean z11 = bundle.getBoolean(Constants.PREFS_KEEP_NOTIF, true);
            boolean z12 = bundle.getBoolean(Constants.PREFS_HIDE_BT_UNLOCK, true);
            boolean z13 = bundle.getBoolean(Constants.PREFS_MUTE_SCREEN_ON, true);
            boolean z14 = bundle.getBoolean(Constants.PREFS_CANCEL_VIBRATE_SCREEN_ON, true);
            boolean z15 = bundle.getBoolean(Constants.PREFS_ALLOW_MANAGE_ALL, true);
            boolean z16 = bundle.getBoolean(Constants.PREFS_ENABLE_LOG, false);
            this.sSinkEnabled = z;
            this.sGlassEnabled = z2;
            this.sHideLockFod = z3;
            sHideLockFodFlag.set(z3);
            this.sHideDismissBtn = z4;
            sHideDismissFlag.set(z4);
            this.sFocusGlass = z5;
            sFocusGlassFlag.set(z5);
            sAodBatterySyncFlag.set(z6);
            sPinGlassFlag.set(z7);
            sQsEditHideFlag.set(z8);
            sNoFoldHistoryFlag.set(z9);
            sNoNotifLimitFlag.set(z10);
            sKeepNotifFlag.set(z11);
            sHideBtUnlockFlag.set(z12);
            sMuteScreenOnFlag.set(z13);
            sCancelVibrateScreenOnFlag.set(z14);
            sAllowManageAllFlag.set(z15);
            LogUtil.setEnabled(z16);
            LogUtil.logAlways("设置(真实值同步)：sink=" + z + "，glass=" + z2 + "，hideLockFod=" + z3 + "，hideDismiss=" + z4 + "，focusGlass=" + z5 + "，aodBattery=" + z6 + "，qsEditHide=" + z8 + "，noFoldHistory=" + z9 + "，noNotifLimit=" + z10 + "，keepNotif=" + z11 + "，hideBtUnlock=" + z12 + "，muteScreenOn=" + z13 + "，allowManageAll=" + z15 + "，日志=" + z16);
        } catch (Throwable th) {
            LogUtil.logAlways("设置(真实值同步) 应用失败: " + th);
        }
    }

    private static Object currentApplication() {
        try {
            return Class.forName("android.app.ActivityThread").getMethod("currentApplication", new Class[0]).invoke(null, new Object[0]);
        } catch (Throwable unused) {
            return null;
        }
    }

    private void installPluginClassLoaderHooks(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            Method declaredMethod = Class.forName(Constants.PLUGIN_FACTORY_CLASS, false, classLoader).getDeclaredMethod(Constants.PLUGIN_CREATE_CLASSLOADER_METHOD, new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("plugin-classloader").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.6
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    Object objProceed = chain.proceed();
                    if (objProceed instanceof ClassLoader) {
                        MainHook.this.tryHookQsEditIn((ClassLoader) objProceed);
                    }
                    return objProceed;
                }
            });
            LogUtil.logAlways("[QS编辑] 已挂钩 PluginFactory.createClassLoader（插件加载时补挂）");
        } catch (Throwable th) {
            LogUtil.logAlways("[QS编辑] 插件工厂挂钩失败: " + th);
        }
    }

    /**
     * 三方主题液态玻璃增强（移植自 HyperChanger，合并进 glass_enabled 开关）。
     * 换用三方/全局主题后 MIUI 会把 defaultTheme 置 false，导致系统材质层与模糊层
     * 切回主题自带样式、柔光玻璃消失。这里在各判定点强制「仍处于默认主题」，玻璃得以保留。
     * 所有 guard 在 intercept 时统一受 sGlassEnabled 控制：开关关闭即完全还原系统行为。
     * 本方法对传入的任何 classloader 都尝试安装，加载不到的类静默跳过，
     * 因此用 systemui 默认 loader 与插件 loader 各调一次即可覆盖两侧。
     */
    private void installThirdPartyThemeGlassHooks(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        // 1) ThemeUtils.getDefaultPluginTheme / getDefaultSysUiTheme → true
        try {
            Class<?> themeUtils = Class.forName(Constants.TPG_THEME_UTILS_CLASS, false, classLoader);
            String[] getters = {"getDefaultPluginTheme", "getDefaultSysUiTheme"};
            for (String m : getters) {
                try {
                    hook(themeUtils.getMethod(m)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("tpg-getter-" + m).intercept(new XposedInterface.Hooker() {
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    if (MainHook.this.sGlassEnabled) {
                                        return true;
                                    }
                                    return chain.proceed();
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            // 2) ThemeUtils.updateDefaultPluginTheme / updateDefaultSysUiTheme → 执行后强制写回 true
            String[] updaters = {"updateDefaultPluginTheme", "updateDefaultSysUiTheme"};
            for (String m : updaters) {
                try {
                    hook(themeUtils.getMethod(m)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("tpg-update-" + m).intercept(new XposedInterface.Hooker() {
                                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                                    Object r = chain.proceed();
                                    if (MainHook.this.sGlassEnabled) {
                                        forceThemeUtilsFlags(themeUtils);
                                    }
                                    return r;
                                }
                            });
                } catch (Throwable ignored) {
                }
            }
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 ThemeUtils getter/updater");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] ThemeUtils 挂钩失败(本 loader 无此类): " + th);
        }

        // 3) MiuiMaterialUtils.onDefaultThemeChanged(Boolean) → 强制 true
        try {
            Class<?> materialUtils = Class.forName(Constants.TPG_MATERIAL_UTILS_CLASS, false, classLoader);
            hook(materialUtils.getMethod("onDefaultThemeChanged", Boolean.TYPE)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-onDefaultThemeChanged").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.this.sGlassEnabled) {
                                return chain.proceed(new Object[]{Boolean.TRUE});
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 MiuiMaterialUtils.onDefaultThemeChanged");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] MiuiMaterialUtils 挂钩失败(本 loader 无此类): " + th);
        }

        // 4) MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme(Context) → true
        try {
            Class<?> blurCompat = Class.forName(Constants.TPG_BLUR_COMPAT_CLASS, false, classLoader);
            hook(blurCompat.getMethod("getBackgroundMaterialOpenedInDefaultTheme", android.content.Context.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-getBgMaterialOpenedInDefaultTheme").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.this.sGlassEnabled) {
                                return true;
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 MiBlurCompat.getBackgroundMaterialOpenedInDefaultTheme");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] MiBlurCompat 挂钩失败(本 loader 无此类): " + th);
        }

        // 5) MiuiDefaultThemeControllerImpl.isDefaultTheme() → true
        try {
            Class<?> controller = Class.forName(Constants.TPG_DEFAULT_THEME_CONTROLLER_CLASS, false, classLoader);
            hook(controller.getMethod("isDefaultTheme")).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-isDefaultTheme").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (MainHook.this.sGlassEnabled) {
                                return true;
                            }
                            return chain.proceed();
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 MiuiDefaultThemeControllerImpl.isDefaultTheme");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] MiuiDefaultThemeControllerImpl 挂钩失败(本 loader 无此类): " + th);
        }

        // 6) ConfigurationControllerImpl.onConfigurationChanged → 执行后强制 sDefaultSysUiTheme=true
        try {
            Class<?> configController = Class.forName(Constants.TPG_CONFIG_CONTROLLER_CLASS, false, classLoader);
            final Field sDefaultField = Class.forName(Constants.TPG_MIUI_THEME_UTILS_CLASS, false, classLoader)
                    .getDeclaredField("sDefaultSysUiTheme");
            sDefaultField.setAccessible(true);
            hook(configController.getMethod("onConfigurationChanged", android.content.res.Configuration.class))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("tpg-configChanged").intercept(new XposedInterface.Hooker() {
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            Object r = chain.proceed();
                            if (MainHook.this.sGlassEnabled) {
                                sDefaultField.setBoolean(null, true);
                            }
                            return r;
                        }
                    });
            LogUtil.logAlways("[三方主题玻璃] 已挂钩 ConfigurationControllerImpl.onConfigurationChanged");
        } catch (Throwable th) {
            LogUtil.log("[三方主题玻璃] ConfigurationControllerImpl 挂钩失败(本 loader 无此类): " + th);
        }
    }

    private static void forceThemeUtilsFlags(Class<?> themeClass) {
        try {
            Field f1 = themeClass.getDeclaredField("defaultPluginTheme");
            f1.setAccessible(true);
            f1.setBoolean(null, true);
            Field f2 = themeClass.getDeclaredField("defaultSysUiTheme");
            f2.setAccessible(true);
            f2.setBoolean(null, true);
        } catch (Throwable ignored) {
        }
    }

    private void installMediaIslandDefense(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.MEDIA_ISLAND_BINDER_CLASS, false, classLoader);
            Class<?> cls2 = Class.forName(Constants.MEDIA_ISLAND_VIEW_HOLDER_CLASS, false, classLoader);
            try {
                hook(cls.getDeclaredMethod("attach", cls2, cls2)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("media-island-attach-guard").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.7
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        try {
                            return chain.proceed();
                        } catch (Throwable th) {
                            if (!LogUtil.hitOnce("island-attach")) {
                                return null;
                            }
                            LogUtil.logAlways("[防御] attach 内崩溃已吞掉（MiPalette）: " + th);
                            return null;
                        }
                    }
                });
                LogUtil.logAlways("[防御] 已挂钩 attach（吞异常版）");
            } catch (Throwable th) {
                LogUtil.logAlways("[防御] attach 挂钩失败: " + th);
            }
            try {
                Method methodFindTwoArgMethod = findTwoArgMethod(cls, "setMusicBgShader");
                if (methodFindTwoArgMethod == null) {
                    LogUtil.log("[防御] setMusicBgShader 未找到（跳过）");
                } else {
                    methodFindTwoArgMethod.setAccessible(true);
                    hook(methodFindTwoArgMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("media-island-shader-guard").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.8
                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            try {
                                return chain.proceed();
                            } catch (Throwable th2) {
                                if (!LogUtil.hitOnce("island-shader")) {
                                    return null;
                                }
                                LogUtil.logAlways("[防御] setMusicBgShader 内崩溃已吞掉（MiPalette）: " + th2);
                                return null;
                            }
                        }
                    });
                    LogUtil.logAlways("[防御] 已挂钩 setMusicBgShader（吞异常版）");
                }
            } catch (Throwable th2) {
                LogUtil.logAlways("[防御] setMusicBgShader 挂钩失败: " + th2);
            }
        } catch (Throwable th3) {
            LogUtil.logAlways("[防御] 媒体岛防御挂钩失败（类未加载等）: " + th3);
        }
    }

    private static Method findTwoArgMethod(Class<?> cls, String str) {
        for (Method method : cls.getDeclaredMethods()) {
            if (method.getName().equals(str) && method.getParameterCount() == 2) {
                return method;
            }
        }
        return null;
    }

    private void installExpandButtonColor(ClassLoader classLoader) {
        if (classLoader == null) {
            return;
        }
        try {
            try {
                hook(View.class.getMethod("setBackground", Drawable.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("expand-button-color-bg").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.9
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!MainHook.this.sGlassEnabled) {
                            return chain.proceed();
                        }
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof View) {
                            View view = (View) thisObject;
                            if (MainHook.isExpandView(view)) {
                                Object[] objArr = {MainHook.makeGlassPill(view.getResources())};
                                if (LogUtil.hitOnce("expand-bg")) {
                                    LogUtil.logAlways("[展开按钮] setBackground 拦截替换为白透 类=" + view.getClass().getName() + " id=0x" + Integer.toHexString(view.getId()));
                                }
                                return chain.proceed(objArr);
                            }
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[展开按钮] 已挂钩 setBackground（v1.6.2 宽泛匹配拦截）");
            } catch (Throwable th) {
                LogUtil.logAlways("[展开按钮] setBackground 挂钩失败: " + th);
            }
            try {
                hook(View.class.getMethod("setBackgroundTintList", ColorStateList.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("expand-button-color-tint").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.10
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (!MainHook.this.sGlassEnabled) {
                            return chain.proceed();
                        }
                        Object thisObject = chain.getThisObject();
                        if (thisObject instanceof View) {
                            View view = (View) thisObject;
                            if (MainHook.isExpandView(view)) {
                                Object[] objArr = {null};
                                if (LogUtil.hitOnce("expand-tint")) {
                                    LogUtil.logAlways("[展开按钮] setBackgroundTintList 拦截清 tint 类=" + view.getClass().getName() + " id=0x" + Integer.toHexString(view.getId()));
                                }
                                return chain.proceed(objArr);
                            }
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[展开按钮] 已挂钩 setBackgroundTintList（v1.6.2 宽泛匹配拦截）");
            } catch (Throwable th2) {
                LogUtil.logAlways("[展开按钮] setBackgroundTintList 挂钩失败: " + th2);
            }
        } catch (Throwable th3) {
            LogUtil.logAlways("[展开按钮] 挂钩失败: " + th3);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isExpandView(View view) {
        try {
            int id = view.getId();
            if (id == -1) {
                return false;
            }
            int iResolveExpandPillId = sExpandPillId;
            if (iResolveExpandPillId == 0 && (iResolveExpandPillId = resolveExpandPillId(view)) == 0) {
                return false;
            }
            if (iResolveExpandPillId < 0) {
                return matchExpandByResourceName(view);
            }
            if (id != iResolveExpandPillId) {
                return false;
            }
            Class<?> cls = sExpandViewClass;
            if (cls != null) {
                return view.getClass() == cls;
            }
            if (Constants.EXPAND_BUTTON_VIEW_CLASS.equals(view.getClass().getName())) {
                sExpandViewClass = view.getClass();
                return true;
            }
            return false;
        } catch (Throwable unused) {
            return false;
        }
    }

    private static int resolveExpandPillId(View view) {
        try {
            Context context = view.getContext();
            int identifier = context.getResources().getIdentifier(Constants.EXPAND_BUTTON_PILL_ID_NAME, "id", context.getPackageName());
            if (identifier == 0) {
                identifier = context.getResources().getIdentifier(Constants.EXPAND_BUTTON_PILL_ID_NAME, "id", Constants.TARGET_PKG);
            }
            sExpandPillId = identifier == 0 ? -1 : identifier;
            LogUtil.log("[展开按钮] 目标 id 解析: expand_button_pill = " + (identifier == 0 ? "失败，走 id 名兜底" : "0x" + Integer.toHexString(identifier)));
            return sExpandPillId;
        } catch (Throwable unused) {
            sExpandPillId = -1;
            return -1;
        }
    }

    private static boolean matchExpandByResourceName(View view) {
        if (Constants.EXPAND_BUTTON_VIEW_CLASS.equals(view.getClass().getName())) {
            return Constants.EXPAND_BUTTON_PILL_ID_NAME.equals(viewIdNameOrNull(view));
        }
        return false;
    }

    private static String viewIdNameOrNull(View view) {
        String resourceEntryName;
        try {
            int id = view.getId();
            if (id == -1 || (resourceEntryName = view.getResources().getResourceEntryName(id)) == null || resourceEntryName.length() == 0 || resourceEntryName.startsWith("0x")) {
                return null;
            }
            return resourceEntryName;
        } catch (Throwable unused) {
        }
        return null;
    }

    private static String viewIdName(View view) {
        String strViewIdNameOrNull = viewIdNameOrNull(view);
        return strViewIdNameOrNull != null ? strViewIdNameOrNull : "0x" + Integer.toHexString(view.getId());
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Drawable makeGlassPill(Resources resources) {
        int i = (resources.getConfiguration().uiMode & 48) == 32 ? Constants.EXPAND_PILL_BG_DARK : Constants.EXPAND_PILL_BG_LIGHT;
        GradientDrawable gradientDrawable = new GradientDrawable();
        gradientDrawable.setShape(0);
        gradientDrawable.setColor(i);
        gradientDrawable.setCornerRadius(resources.getDisplayMetrics().density * 14.0f);
        return gradientDrawable;
    }

    private void installLockFodHooks(ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.MIUI_GXZW_ANIM_MANAGER_CLASS, false, classLoader);
            try {
                hook(cls.getDeclaredMethod(Constants.FOD_GET_FINGER_ICON_RES_METHOD, Boolean.TYPE)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-fod-icon-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.11
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (MainHook.sHideLockFodFlag.get() && MainHook.isLockFodAuthen(chain.getThisObject())) {
                            Context contextLockFodContext = MainHook.lockFodContext(chain.getThisObject());
                            if (contextLockFodContext != null && MainHook.isHideResValid(contextLockFodContext)) {
                                if (LogUtil.hitOnce("fod-icon")) {
                                    LogUtil.logAlways("[锁屏指纹] getFingerIconResource 命中（mKeyguardAuthen=true）→ 返回隐藏资源 0x7f080000");
                                }
                                return Integer.valueOf(Constants.FOD_HIDE_RES_ID);
                            }
                            return chain.proceed();
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[锁屏指纹] 已挂钩 getFingerIconResource(Z)I");
            } catch (Throwable th) {
                LogUtil.logAlways("[锁屏指纹] getFingerIconResource 挂钩失败: " + th);
            }
            try {
                hook(cls.getDeclaredMethod(Constants.FOD_GET_RECOGNIZING_ANIM_METHOD, new Class[0])).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-fod-anim-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.12
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (MainHook.sHideLockFodFlag.get() && MainHook.isLockFodAuthen(chain.getThisObject())) {
                            Object objLockFodAnimZeroItem = MainHook.lockFodAnimZeroItem(chain.getThisObject());
                            if (LogUtil.hitOnce("fod-anim")) {
                                LogUtil.logAlways("[锁屏指纹] getRecognizingAnimItem 命中（mKeyguardAuthen=true）→ 返回空动画条目 " + (objLockFodAnimZeroItem == null ? "null" : objLockFodAnimZeroItem.getClass().getName()));
                            }
                            return objLockFodAnimZeroItem;
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[锁屏指纹] 已挂钩 getRecognizingAnimItem()");
            } catch (Throwable th2) {
                LogUtil.logAlways("[锁屏指纹] getRecognizingAnimItem 挂钩失败: " + th2);
            }
        } catch (Throwable th3) {
            LogUtil.logAlways("[锁屏指纹] 类未找到（MiuiGxzwAnimManager 不存在）: " + th3);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isLockFodAuthen(Object obj) {
        if (obj == null) {
            return false;
        }
        try {
            Field field = sFodAuthenField;
            if (field == null) {
                field = obj.getClass().getField(Constants.FOD_KEYGUARD_AUTHEN_FIELD);
                sFodAuthenField = field;
            }
            return ((Boolean) field.get(obj)).booleanValue();
        } catch (Throwable unused) {
            return false;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Object lockFodAnimZeroItem(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Field field = sFodAnimMapField;
            if (field == null) {
                field = obj.getClass().getField(Constants.FOD_ANIM_ITEM_MAP_FIELD);
                sFodAnimMapField = field;
            }
            Object obj2 = field.get(obj);
            if (obj2 instanceof Map) {
                return ((Map) obj2).get(0);
            }
            return null;
        } catch (Throwable unused) {
            return null;
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static Context lockFodContext(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            Field field = sFodCtxField;
            if (field == null) {
                field = obj.getClass().getField(Constants.MUTE_ALERT_CONTEXT_FIELD);
                sFodCtxField = field;
            }
            Object obj2 = field.get(obj);
            if (obj2 instanceof Context) {
                return (Context) obj2;
            }
        } catch (Throwable unused) {
        }
        return null;
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static boolean isHideResValid(Context context) {
        Boolean bool = sHideResValid;
        if (bool != null) {
            return bool.booleanValue();
        }
        try {
            String resourceEntryName = context.getResources().getResourceEntryName(Constants.FOD_HIDE_RES_ID);
            sHideResValid = Boolean.TRUE;
            LogUtil.logAlways("[锁屏指纹] 隐藏资源 0x7f080000 可解析（" + resourceEntryName + "）");
            return true;
        } catch (Throwable th) {
            sHideResValid = Boolean.FALSE;
            LogUtil.logAlways("[锁屏指纹] 隐藏资源 0x7f080000 不可解析，回退原逻辑: " + th);
            return false;
        }
    }

    private void installHideDismissButtonHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = View.class.getDeclaredMethod("onAttachedToWindow", new Class[0]);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("notif-dismiss-btn-hide").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.13
                private volatile int sId;

                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!MainHook.sHideDismissFlag.get()) {
                        return chain.proceed();
                    }
                    Object thisObject = chain.getThisObject();
                    if (!(thisObject instanceof View)) {
                        return chain.proceed();
                    }
                    View view = (View) thisObject;
                    int identifier = this.sId;
                    if (identifier == 0) {
                        try {
                            Context context = view.getContext();
                            if (context == null) {
                                return chain.proceed();
                            }
                            identifier = context.getResources().getIdentifier(Constants.NOTIF_DISMISS_VIEW_ID_NAME, "id", Constants.TARGET_PKG);
                            if (identifier == 0) {
                                return chain.proceed();
                            }
                            this.sId = identifier;
                            LogUtil.logAlways("[清除按钮] 按钮 id 解析: notification_dismiss_view = " + identifier);
                        } catch (Throwable unused) {
                            return chain.proceed();
                        }
                    }
                    if (view.getId() == identifier) {
                        try {
                            Object parent = view.getParent();
                            View view2 = parent instanceof View ? (View) parent : view;
                            float f = view2.getResources().getDisplayMetrics().density;
                            int i = view2.getResources().getDisplayMetrics().heightPixels;
                            view2.setTranslationX(0.0f);
                            view2.setTranslationY(i + (f * 20.0f));
                            view.setVisibility(4);
                            if (LogUtil.hitOnce("dismiss-hide")) {
                                LogUtil.logAlways("[清除按钮] 已隐藏：图标 INVISIBLE + 容器下移到屏底之外（+" + i + "px）按钮类=" + view.getClass().getName() + " 容器类=" + view2.getClass().getName());
                            }
                        } catch (Throwable unused2) {
                        }
                    }
                    return chain.proceed();
                }
            });
            LogUtil.logAlways("[清除按钮] 已挂钩 View.onAttachedToWindow（id 精准过滤，每次 attach 隐藏）");
        } catch (Throwable th) {
            LogUtil.logAlways("[清除按钮] 挂钩失败: " + th);
        }
    }

    private void installFocusGlassHooks(final ClassLoader classLoader) {
        try {
            Class<?> cls = Class.forName(Constants.FOCUS_BLUR_CLASS, false, classLoader);
            final Class<?> cls2 = Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", false, classLoader);
            hook(cls.getDeclaredMethod("apply", cls2, Context.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("focus-glass-blur-swap").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.14
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    if (!MainHook.sFocusGlassFlag.get()) {
                        return chain.proceed();
                    }
                    Object arg = chain.getArg(0);
                    Object arg2 = chain.getArg(1);
                    try {
                        Class<?> cls3 = Class.forName(Constants.ROW_BLUR_CLASS, false, classLoader);
                        cls3.getMethod("apply", cls2, Context.class).invoke(cls3.getField("INSTANCE").get(null), arg, arg2);
                        return null;
                    } catch (Throwable unused) {
                        return chain.proceed();
                    }
                }
            });
            LogUtil.logAlways("[焦点玻璃] 已挂钩 FocusNotificationBlurEffect.apply → NotificationRowBlurEffect");
        } catch (Throwable th) {
            LogUtil.logAlways("[焦点玻璃] blur 替换挂钩失败: " + th);
        }
        try {
            Context contextCurrentAppContext = currentAppContext();
            if (contextCurrentAppContext != null) {
                fillFocusGlassParams(contextCurrentAppContext, classLoader);
            }
            for (String str : Constants.FOCUS_GLASS_CLASSES) {
                try {
                    Method declaredMethod = Class.forName(str, false, classLoader).getDeclaredMethod("apply", Object.class, Context.class);
                    declaredMethod.setAccessible(true);
                    hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("focus-glass-params-lazy").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.15
                        private boolean sFilled;

                        public Object intercept(XposedInterface.Chain chain) throws Throwable {
                            if (this.sFilled) {
                                return chain.proceed();
                            }
                            if (MainHook.sFocusGlassFlag.get()) {
                                this.sFilled = true;
                                Object arg = chain.getArg(1);
                                if (arg instanceof Context) {
                                    MainHook.fillFocusGlassParams((Context) arg, classLoader);
                                }
                            }
                            return chain.proceed();
                        }
                    });
                } catch (Throwable unused) {
                }
            }
            LogUtil.logAlways("[焦点玻璃] 已挂钩 4 个 Focus 类 apply（懒填充兜底）");
        } catch (Throwable th2) {
            LogUtil.logAlways("[焦点玻璃] glassParamsArray 处理失败: " + th2);
        }
    }

    /* JADX INFO: Access modifiers changed from: private */
    public static void fillFocusGlassParams(Context context, ClassLoader classLoader) {
        try {
            int identifier = context.getResources().getIdentifier(Constants.NORMAL_GLASS_PARAMS_RES, "array", Constants.TARGET_PKG);
            if (identifier == 0) {
                return;
            }
            String[] stringArray = context.getResources().getStringArray(identifier);
            int length = stringArray.length;
            float[] fArr = new float[length];
            for (int i = 0; i < stringArray.length; i++) {
                try {
                    fArr[i] = Float.parseFloat(stringArray[i]);
                } catch (Throwable unused) {
                    fArr[i] = 0.0f;
                }
            }
            for (String str : Constants.FOCUS_GLASS_CLASSES) {
                try {
                    Class.forName(str, false, classLoader).getField("glassParamsArray").set(null, fArr);
                } catch (Throwable unused2) {
                }
            }
            LogUtil.logAlways("[焦点玻璃] 已填充 glassParamsArray（notification_glass_params_normal len=" + length + "）");
        } catch (Throwable unused3) {
        }
    }

    private static Context currentAppContext() {
        try {
            Object objCurrentApplication = currentApplication();
            if (objCurrentApplication instanceof Context) {
                return (Context) objCurrentApplication;
            }
            return null;
        } catch (Throwable unused) {
            return null;
        }
    }

    private void installNotificationSinkHooks(ClassLoader classLoader) {
        LogUtil.logAlways("[下沉] installNotificationSinkHooks 开始，sSinkEnabled=" + this.sSinkEnabled);
        try {
            if (sinkHooked.add(Constants.FOD_SHELF_SPACE_FLOW_CLASS)) {
                hook(Class.forName(Constants.FOD_SHELF_SPACE_FLOW_CLASS, false, classLoader).getDeclaredMethod("invokeSuspend", Object.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-notification-ignore-shelf").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.16
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        if (MainHook.this.sSinkEnabled) {
                            if (LogUtil.hitOnce("sink-flow1")) {
                                LogUtil.logAlways("[下沉] flow1 被调用，sink=" + MainHook.this.sSinkEnabled + " -> 返回 false（下沉）");
                            }
                            return Boolean.FALSE;
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[下沉] 已挂钩 useExtraShelfSpace 通知下沉");
            }
        } catch (Throwable th) {
            LogUtil.logAlways("[下沉] useExtraShelfSpace 挂钩失败（类未找到等 loadClass）: " + th);
            sinkHooked.remove(Constants.FOD_SHELF_SPACE_FLOW_CLASS);
        }
        try {
            if (sinkHooked.add(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS)) {
                final Class<?> cls = Class.forName(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS, false, classLoader);
                hook(cls.getDeclaredMethod("invokeSuspend", Object.class)).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("lockscreen-notification-sink-position").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook.17
                    public Object intercept(XposedInterface.Chain chain) throws Throwable {
                        String name;
                        if (MainHook.this.sSinkEnabled) {
                            try {
                                Field declaredField = MainHook.sFlow2Field;
                                if (declaredField == null) {
                                    declaredField = cls.getDeclaredField("L$1");
                                    declaredField.setAccessible(true);
                                    MainHook.sFlow2Field = declaredField;
                                }
                                Object obj = declaredField.get(chain.getThisObject());
                                if (obj instanceof Object[]) {
                                    Object[] objArr = (Object[]) obj;
                                    if (LogUtil.hitOnce("sink-flow2")) {
                                        LogUtil.logAlways("[下沉] flow2 被调用，sink=" + MainHook.this.sSinkEnabled + "，L$1=Object[] len=" + objArr.length);
                                    }
                                    if (objArr.length > 6) {
                                        objArr[6] = false;
                                    }
                                } else if (LogUtil.hitOnce("sink-flow2-nonarray")) {
                                    StringBuilder sb = new StringBuilder("[下沉] flow2 L$1 非数组: ");
                                    if (obj == null) {
                                        name = "null";
                                    } else {
                                        name = obj.getClass().getName();
                                    }
                                    LogUtil.logAlways(sb.append(name).toString());
                                }
                            } catch (Throwable th2) {
                                if (LogUtil.hitOnce("sink-flow2-fail")) {
                                    LogUtil.logAlways("[下沉] flow2 改 L$1 失败: " + th2);
                                }
                            }
                        }
                        return chain.proceed();
                    }
                });
                LogUtil.logAlways("[下沉] 已挂钩 通知位置 flow");
            }
        } catch (Throwable th2) {
            LogUtil.logAlways("[下沉] 通知位置 flow 挂钩失败（类未找到等 loadClass）: " + th2);
            sinkHooked.remove(Constants.FOD_NOTIFICATION_POSITION_FLOW_CLASS);
        }
    }

    private void installAodBatteryHooks(ClassLoader classLoader) {
        LogUtil.logAlways("[息屏电池] installAodBatteryHooks 调用, flag=" + sAodBatterySyncFlag.get());
        installAodCombineHook(classLoader);
        installAodBatteryModeHook(classLoader);
        installAodFullAodHook(classLoader);
    }

    private void installAodCombineHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.AOD_COMBINE_CLASS, false, classLoader).getDeclaredMethod("invoke", Object.class, Object.class, Object.class);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("aod-battery-combine").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda19
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAodCombineHook$17(chain);
                }
            });
            LogUtil.logAlways("[息屏电池] 已挂钩 combine（AOD 下状态栏可见）");
        } catch (Throwable th) {
            LogUtil.logAlways("[息屏电池] combine 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAodCombineHook$17(XposedInterface.Chain chain) throws Throwable {
        if (!sAodBatterySyncFlag.get()) {
            return chain.proceed();
        }
        Object arg = chain.getArg(1);
        if (arg instanceof Object[]) {
            Object[] objArr = (Object[]) arg;
            if (objArr.length == 9) {
                boolean zEquals = Boolean.TRUE.equals(objArr[3]);
                sAodDozing = zEquals;
                LogUtil.log("[息屏电池] combine dozing=" + zEquals + " fullAod=" + objArr[6]);
                if (zEquals && !Boolean.TRUE.equals(objArr[6])) {
                    objArr[6] = Boolean.TRUE;
                    LogUtil.log("[息屏电池] combine 强制 fullAod=true（AOD 状态栏可见）");
                }
            }
        }
        return chain.proceed();
    }

    private void installAodBatteryModeHook(ClassLoader classLoader) {
        try {
            Method declaredMethod = Class.forName(Constants.AOD_BATTERY_CLASS, false, classLoader).getDeclaredMethod("toggleAodMode", Boolean.TYPE);
            declaredMethod.setAccessible(true);
            hook(declaredMethod).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("aod-battery-mode").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda0
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAodBatteryModeHook$18(chain);
                }
            });
            LogUtil.logAlways("[息屏电池] 已挂钩 com.android.systemui.statusbar.views.MiuiBatteryMeterView.toggleAodMode");
        } catch (Throwable th) {
            LogUtil.logAlways("[息屏电池] toggleAodMode 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAodBatteryModeHook$18(XposedInterface.Chain chain) throws Throwable {
        if (!sAodBatterySyncFlag.get()) {
            return chain.proceed();
        }
        if (Boolean.TRUE.equals(chain.getArg(0))) {
            sAodDozing = true;
            LogUtil.log("[息屏电池] 进入AOD: toggleAodMode(true)→强制false（系统状态栏样式）");
            Object[] array = chain.getArgs().toArray();
            array[0] = Boolean.FALSE;
            return chain.proceed(array);
        }
        sAodDozing = false;
        LogUtil.log("[息屏电池] 退出AOD: toggleAodMode(false), sAodDozing=false");
        return chain.proceed();
    }

    private void installAodFullAodHook(ClassLoader classLoader) {
        try {
            Method method = null;
            for (Class<?> cls = Class.forName(Constants.AOD_KSVC_INJECT_CLASS, false, classLoader); cls != null && method == null; cls = cls.getSuperclass()) {
                for (Method method2 : cls.getDeclaredMethods()) {
                    if (method2.getName().equals("animateFullAod") && method2.getParameterTypes().length == 2) {
                        method = method2;
                        break;
                    }
                }
            }
            if (method == null) {
                LogUtil.logAlways("[息屏电池] 未找到 animateFullAod（含父类链）");
                return;
            }
            method.setAccessible(true);
            String name = method.getDeclaringClass().getName();
            hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("aod-battery-fullaod").intercept(new XposedInterface.Hooker() { // from class: com.abel.hyperosglass.MainHook$$ExternalSyntheticLambda10
                public final Object intercept(XposedInterface.Chain chain) throws Throwable {
                    return MainHook.lambda$installAodFullAodHook$19(chain);
                }
            });
            LogUtil.logAlways("[息屏电池] 已挂钩 " + name + ".animateFullAod");
        } catch (Throwable th) {
            LogUtil.logAlways("[息屏电池] animateFullAod 挂钩失败: " + th);
        }
    }

    static /* synthetic */ Object lambda$installAodFullAodHook$19(XposedInterface.Chain chain) throws Throwable {
        if (!sAodBatterySyncFlag.get()) {
            return chain.proceed();
        }
        boolean zEquals = Boolean.TRUE.equals(chain.getArg(0));
        sAodDozing = !zEquals;
        LogUtil.log("[息屏电池] animateFullAod(a=" + zEquals + ") → sAodDozing=" + sAodDozing);
        return chain.proceed();
    }
}
