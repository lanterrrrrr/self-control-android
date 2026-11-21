package com.focusguard.selfcontrol;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.telecom.TelecomManager;

import java.util.HashSet;
import java.util.Set;

final class ProtectedApps {
    private static final Set<String> SYSTEM_PACKAGES = Set.of(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller");

    private ProtectedApps() { }

    static boolean isProtected(Context context, String packageName) {
        return packageName == null || packageNames(context).contains(packageName);
    }

    static Set<String> packageNames(Context context) {
        Set<String> packages = new HashSet<>(SYSTEM_PACKAGES);
        packages.add(context.getPackageName());

        PackageManager manager = context.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        addMatchingPackages(packages, manager, home, PackageManager.MATCH_DEFAULT_ONLY);
        addMatchingPackages(packages, manager, new Intent(Settings.ACTION_SETTINGS),
                PackageManager.MATCH_SYSTEM_ONLY);
        addMatchingPackages(packages, manager, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                PackageManager.MATCH_SYSTEM_ONLY);
        addMatchingPackages(packages, manager, new Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
                PackageManager.MATCH_SYSTEM_ONLY);

        TelecomManager telecom = context.getSystemService(TelecomManager.class);
        if (telecom != null) {
            addIfPresent(packages, telecom.getDefaultDialerPackage());
            addIfPresent(packages, telecom.getSystemDialerPackage());
        }

        String inputMethod = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (inputMethod != null) {
            ComponentName component = ComponentName.unflattenFromString(inputMethod);
            if (component != null) {
                packages.add(component.getPackageName());
            }
        }
        return packages;
    }

    static String label(Context context, String packageName) {
        PackageManager manager = context.getPackageManager();
        try {
            ApplicationInfo application = manager.getApplicationInfo(packageName, 0);
            return manager.getApplicationLabel(application).toString();
        } catch (PackageManager.NameNotFoundException exception) {
            return packageName;
        }
    }

    private static void addMatchingPackages(Set<String> packages, PackageManager manager,
            Intent intent, int flags) {
        for (ResolveInfo match : manager.queryIntentActivities(intent, flags)) {
            if (match.activityInfo != null) {
                packages.add(match.activityInfo.packageName);
            }
        }
    }

    private static void addIfPresent(Set<String> packages, String packageName) {
        if (packageName != null) {
            packages.add(packageName);
        }
    }
}
