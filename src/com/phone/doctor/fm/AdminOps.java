package com.phone.doctor.fm;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

import org.json.JSONObject;

/** Device-owner operations (Android 5+/6+/7+). Isolated so older devices never load these API references. */
final class AdminOps {
    private static DevicePolicyManager dpm(Context c) {
        return (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }

    private static ComponentName admin(Context c) {
        return new ComponentName(c, AdminReceiver.class);
    }

    static boolean isOwner(Context c) {
        return dpm(c).isDeviceOwnerApp(c.getPackageName());
    }

    static int needApi(String act) {
        return act.endsWith("_permission") ? 23 : act.contains("hide") ? 21 : 24;
    }

    static String inverse(String act) {
        switch (act) {
            case "hide_app": return "unhide_app";
            case "unhide_app": return "hide_app";
            case "suspend_app": return "unsuspend_app";
            case "unsuspend_app": return "suspend_app";
            default: return null;
        }
    }

    static int grantState(Context c, String pkg, String perm) {
        return dpm(c).getPermissionGrantState(admin(c), pkg, perm);
    }

    static boolean setState(Context c, String pkg, String perm, int state) {
        return dpm(c).setPermissionGrantState(admin(c), pkg, perm, state);
    }

    static boolean apply(Context c, String act, String pkg, String perm) {
        DevicePolicyManager d = dpm(c);
        ComponentName a = admin(c);
        switch (act) {
            case "grant_permission": return d.setPermissionGrantState(a, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED);
            case "revoke_permission": return d.setPermissionGrantState(a, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED);
            case "reset_permission": return d.setPermissionGrantState(a, pkg, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT);
            case "hide_app": return d.setApplicationHidden(a, pkg, true);
            case "unhide_app": return d.setApplicationHidden(a, pkg, false);
            case "suspend_app": return d.setPackagesSuspended(a, new String[]{pkg}, true).length == 0;
            case "unsuspend_app": return d.setPackagesSuspended(a, new String[]{pkg}, false).length == 0;
            default: return false;
        }
    }

    /** Undo of a journaled admin action. */
    static boolean undo(Context c, JSONObject e) {
        String pkg = e.optString("aPkg"), perm = e.optString("aPerm");
        int st = e.optInt("aState", -1);
        if (st >= 0) return setState(c, pkg, perm, st);
        return apply(c, e.optString("aAct"), pkg, perm);
    }
}
