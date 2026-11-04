package com.akari.ppx.data;

public final class HookStatusImpl {

    private HookStatusImpl() {
    }

    public static volatile boolean sLegacyHookMode = false;
    public static volatile String sLegacyHookProvider = null;
}
