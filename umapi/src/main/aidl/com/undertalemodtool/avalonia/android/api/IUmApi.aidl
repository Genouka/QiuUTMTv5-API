// Cross-app API of QiuUTMTv5 (UndertaleModTool Avalonia for Android).
//
// This is the CANONICAL copy of the contract: the app builds its service implementation from this
// file (UndertaleModToolAvalonia.Android references it through the "api" git submodule), and it is
// what client apps compile - either by depending on the published AAR, which already contains the
// generated classes, or by copying this file into their own project as
// src/main/aidl/com/undertalemodtool/avalonia/android/api/IUmApi.aidl (the only option for a
// .NET for Android client, which consumes the .aidl through AndroidInterfaceDescription).
//
// Everything that has a structured result is returned as a JSON string, so the AIDL surface stays
// on plain primitives (a Binder parcel cannot carry arbitrary objects, and AIDL parcelables would
// have to be implemented on both sides).
//
// See README.md for binding instructions and for the shape of every JSON document.

package com.undertalemodtool.avalonia.android.api;

interface IUmApi {
    // -- Discovery ---------------------------------------------------------------------------
    // JSON: {ok, apiVersion, appVersion, packageName, descriptor, permission, permissionLevel,
    //        serviceClass, serviceComponent, bindAction, ready, dataLoaded, dataPath, projectName,
    //        runningJobId, activeJobs, scriptRoots[{path, isBuiltIn, exists}]}
    String getApiInfo();

    // -- Scripts -----------------------------------------------------------------------------
    // JSON: {ok, count, scripts[{name, fileName, relativePath, root, isBuiltIn, fullPath}]}
    String listScripts();

    // The three start* methods return JSON: {ok, jobId, state, error}
    // scriptText: the csx source itself (at most 512 KB - Binder transactions are capped at 1 MB).
    // name:       display name / script path used in diagnostics ("inline.csx" when empty).
    // optionsJson may be null; supported keys: {"assumeYes": bool} - the answer the host gives to
    // ScriptQuestion confirmations (default false).
    String startScriptText(String scriptText, String name, String optionsJson);

    // pathOrUri: a filesystem path, a file:// URI or a content:// URI the app is allowed to read.
    String startScriptFile(String pathOrUri, String optionsJson);

    // relativePath: as returned by listScripts() - resolved inside the built-in script folders
    // and inside the user script folder (/sdcard/QiuUTMTv5/Scripts) only.
    String startBuiltinScript(String relativePath, String optionsJson);

    // -- Jobs --------------------------------------------------------------------------------
    // JSON: {ok, id, kind, name, source, state, queuedAt, startedAt, finishedAt, outputLength,
    //        progress, progressMaximum, progressMessage, progressStatus, returnValue, returnType,
    //        consoleText, error, errorTitle, cancelRequested, assumeYes}
    String getJob(String jobId);

    // Incremental log read; returns JSON {ok, id, state, offset, nextOffset, eof, text}. The caller
    // passes the nextOffset of the previous call to continue reading. Chunks are cut on char
    // boundaries.
    String getJobOutput(String jobId, int fromOffset);

    // Best effort: a queued job stops immediately, a running script stops at its next
    // progress/message call (scripts that never report progress run to completion).
    boolean cancelJob(String jobId);

    // Blocks the calling (Binder) thread until the job finishes or the timeout elapses;
    // returns true when the job has finished. Call it from a worker thread, not from a UI thread.
    boolean waitForJob(String jobId, long timeoutMillis);

    // JSON: {ok, count, jobs[...]} - metadata only, no output text.
    String listJobs();

    // Removes all finished jobs from the list, returns how many were removed.
    int clearFinishedJobs();

    // -- Data files --------------------------------------------------------------------------
    // Both calls block until the file is fully read/written (a large data file can take a while).
    // JSON: {ok, path, dataPath, importantWarnings, warnings[], error}
    String loadDataFile(String pathOrUri);

    // JSON: {ok, path, dataPath, error}
    String saveDataFile(String pathOrUri);
}
