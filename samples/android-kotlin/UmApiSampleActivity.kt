package com.undertalemodtool.avalonia.android.api.sample

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.undertalemodtool.avalonia.android.api.client.UmApiClient
import com.undertalemodtool.avalonia.android.api.client.UmApiException

/**
 * Minimal host app: binds QiuUTMTv5, lists its scripts, runs one and shows the output.
 *
 * The manifest needs (see ../README.md):
 *
 *     <uses-permission android:name="com.undertalemodtool.avalonia.android.permission.RUN_SCRIPT" />
 *     <queries><package android:name="com.undertalemodtool.avalonia.android" /></queries>
 *
 * and the app has to be signed with QiuUTMTv5's certificate for the permission to be granted.
 */
class UmApiSampleActivity : Activity() {

    private var client: UmApiClient? = null
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        output = TextView(this).apply { setTextIsSelectable(true) }
        val list = Button(this).apply { text = "List scripts" }
        val run = Button(this).apply { text = "Run a script" }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(list)
            addView(run)
            addView(output)
        })

        val api = UmApiClient(this)
        client = api

        // connect() is safe on the main thread; the callbacks arrive here too. The blocking calls
        // below are handed to the client's own worker thread.
        api.connect(10_000) { connected ->
            connected.execute {
                try {
                    val info = connected.apiInfo()
                    Log.i(TAG, "connected to QiuUTMTv5, apiVersion=" + info.optString("apiVersion"))
                } catch (e: UmApiException) {
                    Log.w(TAG, "apiInfo failed", e)
                }
            }
        }

        list.setOnClickListener {
            val api2 = client ?: return@setOnClickListener
            api2.execute {
                try {
                    val scripts = api2.listScripts()
                    Log.i(TAG, "found " + scripts.size + " scripts")
                    for (script in scripts.take(20)) {
                        Log.i(TAG, " - " + script.relativePath)
                    }
                } catch (e: UmApiException) {
                    Log.w(TAG, "listScripts failed", e)
                }
            }
        }

        run.setOnClickListener {
            val api2 = client ?: return@setOnClickListener
            api2.runScriptAsync(
                SCRIPT,
                "sample.csx",
                false, // assumeYes: answer ScriptQuestion confirmations with "no"
                { chunk -> output.append(chunk) },
                { job, error ->
                    output.append(
                        if (error != null) "\n[error] " + error.message + "\n"
                        else "\n[" + job.state + "] return=" + job.returnValue + "\n"
                    )
                }
            )
        }
    }

    override fun onDestroy() {
        client?.close()
        client = null
        super.onDestroy()
    }

    private companion object {
        const val TAG = "UmApiSample"

        /** A csx script: plain C# against the tool's ScriptGlobals. */
        val SCRIPT = """
            // Called from another app through the cross-app API.
            SetUMTConsoleText("hello from the SDK sample");
            SetProgressMessage("sample done");
            return "ok";
        """.trimIndent()
    }
}
