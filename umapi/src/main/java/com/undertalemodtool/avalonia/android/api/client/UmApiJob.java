package com.undertalemodtool.avalonia.android.api.client;

import org.json.JSONObject;

/**
 * One script or data-file request as the tool sees it.
 *
 * <p>Instances are immutable snapshots: {@link UmApiClient#getJob} reads the current state, and the
 * output text itself is fetched separately with {@link UmApiClient#getJobOutput} (or delivered
 * through an {@link UmApiClient.OnOutputListener} while the job runs).
 */
public final class UmApiJob {

    /** Request was accepted and is waiting for a free slot (only one job runs at a time). */
    public static final String STATE_QUEUED = "Queued";
    public static final String STATE_RUNNING = "Running";
    public static final String STATE_SUCCEEDED = "Succeeded";
    public static final String STATE_FAILED = "Failed";
    public static final String STATE_CANCELLED = "Cancelled";

    /** Id to pass to the other job calls. */
    public final String id;

    /** "Script", "BuiltinScript", "ScriptFile", "LoadDataFile" or "SaveDataFile". */
    public final String kind;

    /** Display name (the script path, or "inline.csx"). */
    public final String name;

    /** Where the script came from: an inline label, a path, or a content:// URI. */
    public final String source;

    /** One of the {@code STATE_*} constants. */
    public final String state;

    /** Value a csx script returned through {@code SetReturnValue}, when it has one. */
    public final String returnValue;

    /** Type name of {@link #returnValue}, or null. */
    public final String returnType;

    /** Failure text (the same text the tool would show), or null. */
    public final String error;

    /** Short failure title, or null. */
    public final String errorTitle;

    /** Everything the script printed through {@code SetUMTConsoleText}. */
    public final String consoleText;

    /** Milliseconds since the epoch; 0 while the field is unset. */
    public final long queuedAt;
    public final long startedAt;
    public final long finishedAt;

    /** Length of the job's log; pass offsets below it to {@link UmApiClient#getJobOutput}. */
    public final int outputLength;

    public final double progress;
    public final double progressMaximum;
    public final String progressMessage;
    public final String progressStatus;

    /** Whether a cancel was requested (the script may still be finishing). */
    public final boolean cancelRequested;

    /** The answer the host gives to {@code ScriptQuestion} confirmations. */
    public final boolean assumeYes;

    private UmApiJob(JSONObject json) {
        this.id = json.optString("id", "");
        this.kind = json.optString("kind", "");
        this.name = json.optString("name", "");
        this.source = json.optString("source", "");
        this.state = json.optString("state", "");
        this.returnValue = json.isNull("returnValue") ? null : json.optString("returnValue", null);
        this.returnType = json.isNull("returnType") ? null : json.optString("returnType", null);
        this.error = json.isNull("error") ? null : json.optString("error", null);
        this.errorTitle = json.isNull("errorTitle") ? null : json.optString("errorTitle", null);
        this.consoleText = json.optString("consoleText", "");
        this.queuedAt = json.optLong("queuedAt", 0L);
        this.startedAt = json.optLong("startedAt", 0L);
        this.finishedAt = json.optLong("finishedAt", 0L);
        this.outputLength = json.optInt("outputLength", 0);
        this.progress = json.optDouble("progress", 0d);
        this.progressMaximum = json.optDouble("progressMaximum", 0d);
        this.progressMessage = json.isNull("progressMessage") ? null : json.optString("progressMessage", null);
        this.progressStatus = json.optString("progressStatus", "");
        this.cancelRequested = json.optBoolean("cancelRequested", false);
        this.assumeYes = json.optBoolean("assumeYes", false);
    }

    /** Parses the {@code job} documents returned by the service. */
    public static UmApiJob from(JSONObject json) {
        return new UmApiJob(json);
    }

    public boolean isFinished() {
        return isSucceeded() || isFailed() || isCancelled();
    }

    public boolean isSucceeded() {
        return STATE_SUCCEEDED.equals(state);
    }

    public boolean isFailed() {
        return STATE_FAILED.equals(state);
    }

    public boolean isCancelled() {
        return STATE_CANCELLED.equals(state);
    }

    @Override
    public String toString() {
        return "UmApiJob{" + id + ", " + kind + ", " + name + ", " + state
            + (error == null ? "" : ", error=" + error) + "}";
    }
}
