package com.undertalemodtool.avalonia.android.api.client;

/**
 * Names and capability probes for the QiuUTMTv5 cross-app API.
 *
 * <p>Nothing here needs the tool to be installed; the constants are the public part of the
 * contract, so a client can declare them in its manifest without taking any dependency.
 */
public final class UmApi {

    /** Application id of QiuUTMTv5 (UndertaleModTool Avalonia for Android). */
    public static final String PACKAGE = "com.undertalemodtool.avalonia.android";

    /**
     * Permission a client must declare to be allowed to bind the API service.
     *
     * <p>The app declares it as {@code signature|dangerous}, which Android reads as
     * signature-or-system: apps signed with the app's own certificate (and preinstalled apps) get
     * it automatically, other apps cannot obtain it through a user prompt.
     */
    public static final String PERMISSION = PACKAGE + ".permission.RUN_SCRIPT";

    /** Action to bind with (an explicit component works too, see {@code getApiInfo}). */
    public static final String ACTION_BIND = PACKAGE + ".api.action.BIND_UM_API";

    /**
     * Version of the API surface. A client can compare it with
     * {@code getApiInfo()}'s {@code apiVersion} to detect a tool that is too old or too new.
     */
    public static final String API_VERSION = "1";

    /** Largest script {@link UmApiClient#startScript} accepts (Binder caps a transaction at 1 MB). */
    public static final int MAX_INLINE_SCRIPT_CHARS = 512 * 1024;

    private UmApi() {
    }

    /** Whether QiuUTMTv5 is installed for the current user. */
    public static boolean isToolInstalled(android.content.Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Whether this app holds {@link #PERMISSION} (always false for a differently signed app). */
    public static boolean hasPermission(android.content.Context context) {
        // PackageManager.checkPermission instead of Context.checkSelfPermission: the latter only
        // exists from API 23, and this library supports older clients.
        return context.getPackageManager().checkPermission(PERMISSION, context.getPackageName())
            == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Installed and permitted - i.e. a bind is expected to succeed. */
    public static boolean isAvailable(android.content.Context context) {
        return isToolInstalled(context) && hasPermission(context);
    }
}
