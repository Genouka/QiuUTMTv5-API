package com.undertalemodtool.avalonia.android.api.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import com.undertalemodtool.avalonia.android.api.IUmApi;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Connection to a running QiuUTMTv5, and the SDK's entry point.
 *
 * <h2>Threading</h2>
 * Every listener is invoked on the main thread. The API calls themselves block - a script runs on
 * the tool's own thread, but {@code waitForJob}, {@code loadDataFile} and {@code saveDataFile} hold
 * the Binder thread until they are done - so call the blocking methods from a worker thread. The
 * client owns one such thread: {@link #execute} runs work on it, {@link #runScriptAsync} and
 * friends use it, and {@link #bindBlocking} is documented to be called from it.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * UmApiClient client = new UmApiClient(context);
 * client.connect(5000, new UmApiClient.OnConnectedListener() {
 *     public void onConnected(UmApiClient c) {
 *         c.runScriptAsync("SetUMTConsoleText(\"hi\");", "demo.csx", false,
 *             chunk -> Log.i("demo", chunk),
 *             (job, error) -> Log.i("demo", error != null ? error.getMessage() : job.state));
 *     }
 *     public void onConnectionFailed(String message) { Log.w("demo", message); }
 * });
 * // ... later
 * client.close();
 * }</pre>
 */
public final class UmApiClient implements ServiceConnection, Closeable {

    /** Result of {@link #connect}; exactly one method is called, on the main thread. */
    public interface OnConnectedListener {
        void onConnected(UmApiClient client);

        void onConnectionFailed(String message);
    }

    /** Receives the running job's log as it grows. Called on the main thread. */
    public interface OnOutputListener {
        void onOutput(String chunk);
    }

    /** Result of an asynchronous request; exactly one argument is non-null. Called on the main thread. */
    public interface OnJobFinishedListener {
        void onJobFinished(UmApiJob job, UmApiException error);
    }

    /** Result of an asynchronous data-file request; exactly one argument is non-null. */
    public interface OnResultListener {
        void onResult(JSONObject result, UmApiException error);
    }

    /** The blocking call shape, so RemoteException can be handled in one place. */
    private interface Call<T> {
        T invoke() throws RemoteException, UmApiException;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "UmApiClient");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Object connectionLock = new Object();
    private final List<OnConnectedListener> pendingListeners = new ArrayList<>();

    private volatile IUmApi api;
    private volatile boolean connected;
    private boolean bound;

    public UmApiClient(Context context) {
        this.context = context.getApplicationContext();
    }

    // -- Connection --------------------------------------------------------------------------

    /**
     * Binds and waits for the tool to answer.
     *
     * <p>Blocks the calling thread, and the answer arrives on the main thread - so do NOT call this
     * from the main thread (use {@link #connect} there). The worker thread of
     * {@link #execute} is a good place.
     *
     * @param timeoutMillis how long to wait for the bind, in milliseconds.
     * @throws UmApiException when the tool is missing, this app lacks the permission, the bind was
     *     refused, or nothing answered in time.
     */
    public static UmApiClient bindBlocking(Context context, long timeoutMillis) throws UmApiException {
        final UmApiClient client = new UmApiClient(context);
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] failure = new String[1];

        client.connect(timeoutMillis, new OnConnectedListener() {
            @Override
            public void onConnected(UmApiClient connected) {
                latch.countDown();
            }

            @Override
            public void onConnectionFailed(String message) {
                failure[0] = message;
                latch.countDown();
            }
        });

        try {
            if (!latch.await(Math.max(timeoutMillis, 1L) + 5000L, TimeUnit.MILLISECONDS)) {
                client.close();
                throw new UmApiException("Timed out waiting for QiuUTMTv5 to answer the bind.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            client.close();
            throw new UmApiException("Interrupted while binding to QiuUTMTv5.", e);
        }

        if (failure[0] != null) {
            client.close();
            throw new UmApiException(failure[0]);
        }

        return client;
    }

    /**
     * Binds without blocking; safe to call from the main thread.
     *
     * <p>{@code timeoutMillis > 0} fails the connection when the tool does not answer in time
     * (normally it answers in milliseconds; a cold start can take a few seconds). Callbacks always
     * arrive on the main thread.
     */
    public void connect(final long timeoutMillis, final OnConnectedListener listener) {
        if (listener == null) {
            throw new NullPointerException("listener");
        }

        synchronized (connectionLock) {
            if (connected) {
                postConnected(listener);
                return;
            }
            pendingListeners.add(listener);
            if (bound) {
                return;
            }
        }

        if (!UmApi.isToolInstalled(context)) {
            failAll("QiuUTMTv5 (" + UmApi.PACKAGE + ") is not installed.");
            return;
        }
        if (!UmApi.hasPermission(context)) {
            failAll("This app does not hold " + UmApi.PERMISSION + ". The permission is declared as"
                + " signature|dangerous, so it is only granted to apps signed with QiuUTMTv5's own"
                + " certificate (or preinstalled ones).");
            return;
        }

        Intent intent = new Intent(UmApi.ACTION_BIND).setPackage(UmApi.PACKAGE);
        boolean accepted;
        try {
            accepted = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            failAll("The bind was refused: " + e.getMessage());
            return;
        }

        if (!accepted) {
            failAll("bindService() failed. Make sure the manifest declares <uses-permission"
                + " android:name=\"" + UmApi.PERMISSION + "\" /> and a <queries> entry for "
                + UmApi.PACKAGE + " (package visibility, Android 11+).");
            return;
        }

        synchronized (connectionLock) {
            bound = true;
        }

        if (timeoutMillis > 0) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    long deadline = System.currentTimeMillis() + timeoutMillis;
                    synchronized (connectionLock) {
                        while (!connected && System.currentTimeMillis() < deadline) {
                            try {
                                connectionLock.wait(Math.max(1L, deadline - System.currentTimeMillis()));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                        if (connected) {
                            return;
                        }
                    }
                    failAll("Timed out waiting for QiuUTMTv5 to answer the bind.");
                }
            });
        }
    }

    /** Unbinds; the connection object cannot be reused afterwards. */
    public void disconnect() {
        List<OnConnectedListener> abandoned;
        synchronized (connectionLock) {
            connected = false;
            api = null;
            abandoned = new ArrayList<>(pendingListeners);
            pendingListeners.clear();
        }

        if (bound) {
            bound = false;
            try {
                context.unbindService(this);
            } catch (IllegalArgumentException ignored) {
                // Already unbound (the process died, or close() ran twice).
            }
        }

        for (OnConnectedListener listener : abandoned) {
            postConnectionFailed(listener, "Disconnected.");
        }
    }

    /** {@link #disconnect()} plus a shutdown of the client's worker thread. */
    @Override
    public void close() {
        disconnect();
        worker.shutdownNow();
    }

    public boolean isConnected() {
        return connected;
    }

    /**
     * The raw AIDL interface, for calls this SDK does not wrap. Null while disconnected; check
     * {@link #isConnected} first.
     */
    public IUmApi binder() {
        return api;
    }

    /** Runs work on the client's worker thread - the thread the blocking calls belong on. */
    public void execute(Runnable work) {
        worker.execute(work);
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        IUmApi target = service == null ? null : IUmApi.Stub.asInterface(service);
        if (target == null) {
            failAll("QiuUTMTv5 refused the bind (it requires " + UmApi.PERMISSION + ").");
            return;
        }

        api = target;
        connected = true;
        synchronized (connectionLock) {
            connectionLock.notifyAll();
        }

        List<OnConnectedListener> listeners;
        synchronized (connectionLock) {
            listeners = new ArrayList<>(pendingListeners);
            pendingListeners.clear();
        }
        for (final OnConnectedListener listener : listeners) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    listener.onConnected(UmApiClient.this);
                }
            });
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        connected = false;
        api = null;
    }

    // -- Discovery ---------------------------------------------------------------------------

    /** The tool's status: apiVersion, ready, dataLoaded, serviceComponent, script roots, ... */
    public JSONObject apiInfo() throws UmApiException {
        return check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().getApiInfo();
            }
        }));
    }

    /** The csx files the tool can run. */
    public List<UmApiScript> listScripts() throws UmApiException {
        JSONObject json = check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().listScripts();
            }
        }));

        JSONArray array = json.optJSONArray("scripts");
        if (array == null) {
            return Collections.emptyList();
        }
        List<UmApiScript> scripts = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry != null) {
                scripts.add(UmApiScript.from(entry));
            }
        }
        return scripts;
    }

    // -- Running scripts ---------------------------------------------------------------------

    /**
     * Starts an inline script; returns the job id immediately.
     *
     * @param scriptText csx source, at most {@link UmApi#MAX_INLINE_SCRIPT_CHARS} characters.
     * @param name display name used in diagnostics ("inline.csx" when null/empty).
     * @param assumeYes the answer the host gives to {@code ScriptQuestion} confirmations.
     */
    public String startScript(final String scriptText, final String name, final boolean assumeYes)
        throws UmApiException {
        if (scriptText == null || scriptText.trim().isEmpty()) {
            throw new UmApiException("The script text is empty.");
        }
        if (scriptText.length() > UmApi.MAX_INLINE_SCRIPT_CHARS) {
            throw new UmApiException("The script is " + scriptText.length() + " characters long; the"
                + " API accepts at most " + UmApi.MAX_INLINE_SCRIPT_CHARS + ". Write it to a file and"
                + " use startScriptFile() instead.");
        }

        final String options = options(assumeYes);
        return started(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().startScriptText(scriptText, name, options);
            }
        }));
    }

    /** Starts a script the tool ships (or one in the user script folder), by relative path. */
    public String startBuiltinScript(final String relativePath, final boolean assumeYes)
        throws UmApiException {
        final String options = options(assumeYes);
        return started(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().startBuiltinScript(relativePath, options);
            }
        }));
    }

    /** Starts a script from a path, a file:// URI or a content:// URI. */
    public String startScriptFile(final String pathOrUri, final boolean assumeYes)
        throws UmApiException {
        final String options = options(assumeYes);
        return started(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().startScriptFile(pathOrUri, options);
            }
        }));
    }

    /** Current state of a job. */
    public UmApiJob getJob(final String jobId) throws UmApiException {
        return UmApiJob.from(check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().getJob(jobId);
            }
        })));
    }

    /**
     * Reads the job's log from {@code fromOffset} on.
     *
     * <p>Returns {@code {ok, id, state, offset, nextOffset, eof, text}}; feeding {@code nextOffset}
     * back in continues where the previous call stopped, which is exactly what
     * {@link #awaitJob} does.
     */
    public JSONObject getJobOutput(final String jobId, final int fromOffset) throws UmApiException {
        return check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().getJobOutput(jobId, fromOffset);
            }
        }));
    }

    /** Every job the tool still remembers (finished ones included until they are cleared). */
    public List<UmApiJob> listJobs() throws UmApiException {
        JSONObject json = check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().listJobs();
            }
        }));

        JSONArray array = json.optJSONArray("jobs");
        if (array == null) {
            return Collections.emptyList();
        }
        List<UmApiJob> jobs = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.optJSONObject(i);
            if (entry != null) {
                jobs.add(UmApiJob.from(entry));
            }
        }
        return jobs;
    }

    /**
     * Asks the tool to stop a job.
     *
     * <p>Cooperative: a queued job is dropped, a running script stops at its next progress or
     * message call, and a script that never calls one runs to completion.
     */
    public boolean cancelJob(final String jobId) throws UmApiException {
        return call(new Call<Boolean>() {
            @Override
            public Boolean invoke() throws RemoteException, UmApiException {
                return requireApi().cancelJob(jobId);
            }
        });
    }

    /** Forgets every finished job; returns how many were removed. */
    public int clearFinishedJobs() throws UmApiException {
        return call(new Call<Integer>() {
            @Override
            public Integer invoke() throws RemoteException, UmApiException {
                return requireApi().clearFinishedJobs();
            }
        });
    }

    /**
     * Waits for a job to finish, streaming its output to {@code output} (which may be null) and
     * returning the final state. Blocks the calling thread.
     */
    public UmApiJob awaitJob(final String jobId, OnOutputListener output) throws UmApiException {
        final int[] offset = { 0 };

        while (true) {
            boolean finished = call(new Call<Boolean>() {
                @Override
                public Boolean invoke() throws RemoteException, UmApiException {
                    return requireApi().waitForJob(jobId, 1000L);
                }
            });

            if (output != null) {
                JSONObject chunk = getJobOutput(jobId, offset[0]);
                offset[0] = chunk.optInt("nextOffset", offset[0]);
                String text = chunk.optString("text", "");
                if (!text.isEmpty()) {
                    output.onOutput(text);
                }
            }

            if (finished) {
                return getJob(jobId);
            }
        }
    }

    /** Start plus {@link #awaitJob}: runs a script and returns once it finished. Blocking. */
    public UmApiJob runScript(String scriptText, String name, boolean assumeYes, OnOutputListener output)
        throws UmApiException {
        return awaitJob(startScript(scriptText, name, assumeYes), output);
    }

    /** {@link #runScript} on the client's worker thread; the listener runs on the main thread. */
    public void runScriptAsync(final String scriptText, final String name, final boolean assumeYes,
                               final OnOutputListener output, final OnJobFinishedListener finished) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final UmApiJob job = runScript(scriptText, name, assumeYes, output);
                    postFinished(finished, job, null);
                } catch (final UmApiException e) {
                    postFinished(finished, null, e);
                }
            }
        });
    }

    /** Same as {@link #startBuiltinScript} + {@link #awaitJob}, asynchronously. */
    public void runBuiltinScriptAsync(final String relativePath, final boolean assumeYes,
                                      final OnOutputListener output, final OnJobFinishedListener finished) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final UmApiJob job = awaitJob(startBuiltinScript(relativePath, assumeYes), output);
                    postFinished(finished, job, null);
                } catch (final UmApiException e) {
                    postFinished(finished, null, e);
                }
            }
        });
    }

    // -- Data files --------------------------------------------------------------------------

    /**
     * Loads a data file into the tool (blocking; a large file takes a while).
     *
     * <p>Both load and save bypass the tool's own file window: the file is read directly and the
     * tool's data is replaced when the read succeeded.
     */
    public JSONObject loadDataFile(final String pathOrUri) throws UmApiException {
        return check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().loadDataFile(pathOrUri);
            }
        }));
    }

    /** Writes the tool's current data to a path or URI (blocking). */
    public JSONObject saveDataFile(final String pathOrUri) throws UmApiException {
        return check(call(new Call<String>() {
            @Override
            public String invoke() throws RemoteException, UmApiException {
                return requireApi().saveDataFile(pathOrUri);
            }
        }));
    }

    /** {@link #loadDataFile} on the client's worker thread. */
    public void loadDataFileAsync(final String pathOrUri, final OnResultListener listener) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject result = loadDataFile(pathOrUri);
                    postResult(listener, result, null);
                } catch (final UmApiException e) {
                    postResult(listener, null, e);
                }
            }
        });
    }

    /** {@link #saveDataFile} on the client's worker thread. */
    public void saveDataFileAsync(final String pathOrUri, final OnResultListener listener) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject result = saveDataFile(pathOrUri);
                    postResult(listener, result, null);
                } catch (final UmApiException e) {
                    postResult(listener, null, e);
                }
            }
        });
    }

    // -- Plumbing ----------------------------------------------------------------------------

    private static String options(boolean assumeYes) {
        return "{\"assumeYes\":" + assumeYes + "}";
    }

    private static String started(String json) throws UmApiException {
        JSONObject result = check(json);
        String jobId = result.optString("jobId", "");
        if (jobId.isEmpty()) {
            throw new UmApiException("The tool accepted the request but returned no job id.");
        }
        return jobId;
    }

    private IUmApi requireApi() throws UmApiException {
        IUmApi target = api;
        if (target == null) {
            throw new UmApiException("Not connected to QiuUTMTv5: call connect() or bindBlocking() first.");
        }
        return target;
    }

    private <T> T call(Call<T> body) throws UmApiException {
        try {
            return body.invoke();
        } catch (RemoteException e) {
            connected = false;
            api = null;
            throw new UmApiException("QiuUTMTv5 is gone: " + e.getMessage(), e);
        } catch (SecurityException e) {
            throw new UmApiException("The tool rejected the call: " + e.getMessage(), e);
        }
    }

    private static JSONObject check(String json) throws UmApiException {
        if (json == null || json.isEmpty()) {
            throw new UmApiException("The tool returned an empty response.");
        }

        JSONObject result;
        try {
            result = new JSONObject(json);
        } catch (JSONException e) {
            throw new UmApiException("Unreadable response from the tool: " + json, e);
        }

        if (!result.optBoolean("ok", false)) {
            String error = result.optString("error", "");
            throw new UmApiException(error.isEmpty() ? "The tool reported a failure." : error);
        }
        return result;
    }

    private void failAll(final String message) {
        List<OnConnectedListener> listeners;
        synchronized (connectionLock) {
            listeners = new ArrayList<>(pendingListeners);
            pendingListeners.clear();
        }
        for (OnConnectedListener listener : listeners) {
            postConnectionFailed(listener, message);
        }
    }

    private void postConnected(final OnConnectedListener listener) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnected(UmApiClient.this);
            }
        });
    }

    private void postConnectionFailed(final OnConnectedListener listener, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onConnectionFailed(message);
            }
        });
    }

    private void postFinished(final OnJobFinishedListener listener, final UmApiJob job,
                              final UmApiException error) {
        if (listener == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onJobFinished(job, error);
            }
        });
    }

    private void postResult(final OnResultListener listener, final JSONObject result,
                            final UmApiException error) {
        if (listener == null) {
            return;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onResult(result, error);
            }
        });
    }
}
