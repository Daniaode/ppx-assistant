package com.akari.ppx.xp;

import java.lang.reflect.Field;

import de.robv.android.xposed.XposedBridge;

public final class LegacyHookStatusInit {

    private LegacyHookStatusInit() {
    }

    public static void init(ClassLoader classLoader) throws ReflectiveOperationException {
        Class<?> statusClass = classLoader.loadClass("com.akari.ppx.data.HookStatusImpl");
        Field hookModeField = statusClass.getDeclaredField("sLegacyHookMode");
        hookModeField.setAccessible(true);
        hookModeField.set(null, true);
        String provider = detectProvider();
        if (provider != null) {
            Field providerField = statusClass.getDeclaredField("sLegacyHookProvider");
            providerField.setAccessible(true);
            providerField.set(null, provider);
        }
    }

    private static String detectProvider() {
        boolean dexObfuscated = !"de.robv.android.xposed.XposedBridge".equals(XposedBridge.class.getName());
        if (dexObfuscated) {
            return "LSPosed";
        }
        try {
            String tag = (String) XposedBridge.class.getDeclaredField("TAG").get(null);
            if (tag == null) {
                return null;
            }
            if (tag.startsWith("LSPosed")) {
                return "LSPosed";
            }
            if (tag.startsWith("EdXposed")) {
                return "EdXposed";
            }
            if (tag.startsWith("PineXposed")) {
                return "Dreamland";
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }
}
