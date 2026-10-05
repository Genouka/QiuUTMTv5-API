/**
 * Client SDK for the QiuUTMTv5 cross-app API.
 *
 * <p>{@link UmApi} holds the names a client has to know without depending on the tool's APK,
 * {@link UmApiClient} is the connection (bind, run scripts, stream their output, load/save data
 * files), and {@link UmApiJob} / {@link UmApiScript} are the parsed results.
 *
 * <p>The wire contract itself is {@code IUmApi.aidl} in this repository; the generated
 * {@code IUmApi} interface lives in the parent package and is what the AAR ships.
 */
package com.undertalemodtool.avalonia.android.api.client;
