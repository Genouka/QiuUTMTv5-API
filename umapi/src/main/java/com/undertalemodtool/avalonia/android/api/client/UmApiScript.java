package com.undertalemodtool.avalonia.android.api.client;

import org.json.JSONObject;

/**
 * One entry of {@link UmApiClient#listScripts} - a csx file the tool can run.
 */
public final class UmApiScript {

    /** Name without the extension, as the tool's script list shows it. */
    public final String name;

    /** File name with the extension. */
    public final String fileName;

    /** Path relative to its root; this is what {@link UmApiClient#startBuiltinScript} expects. */
    public final String relativePath;

    /** Absolute path of the folder the script lives in. */
    public final String root;

    /** True for the scripts shipped with the tool, false for the user's own scripts. */
    public final boolean builtIn;

    /** Absolute path of the script file. */
    public final String fullPath;

    private UmApiScript(JSONObject json) {
        this.name = json.optString("name", "");
        this.fileName = json.optString("fileName", "");
        this.relativePath = json.optString("relativePath", "");
        this.root = json.optString("root", "");
        this.builtIn = json.optBoolean("isBuiltIn", false);
        this.fullPath = json.optString("fullPath", "");
    }

    public static UmApiScript from(JSONObject json) {
        return new UmApiScript(json);
    }

    @Override
    public String toString() {
        return "UmApiScript{" + relativePath + (builtIn ? ", built-in" : "") + "}";
    }
}
