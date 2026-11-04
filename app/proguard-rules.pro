-repackageclasses "ppx"

-keep class com.akari.ppx.xp.Entry {
    <init>();
}

-keep class com.akari.ppx.xp.ModuleEntryBridge {
    *;
}

-keep class com.akari.ppx.xp.modern.Api101Entry {
    <init>(...);
    *;
}

-keep class com.akari.ppx.data.model.**{*;}
-keep interface com.akari.ppx.xp.hook.BaseHook
-keep class * implements com.akari.ppx.xp.hook.BaseHook {*;}

-keep class com.akari.ppx.ui.MainActivity$Companion {
    boolean isModuleActive(...);
}
-keep class com.akari.ppx.data.alive.AliveActivity$Companion

-keep class * implements com.coremedia.iso.boxes.Box {*;}

-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Signature

-dontpreverify
-overloadaggressively

-dontwarn io.github.libxposed.api.**
