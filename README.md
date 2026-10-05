<div align="center">

# QiuUTMTv5-API

**SDK for driving [QiuUTMTv5](https://github.com/Genouka/QiuUTMTv5) (UndertaleModTool Avalonia for
Android) from another app.**

Run `csx` scripts inside the tool, stream their output, load and save data files - all through a
bound `Service`, protected by a custom permission.

`Android` · `AIDL` · `AAR + JAR` · `Apache-2.0`

</div>

---

## What this is

QiuUTMTv5 hosts an UndertaleModTool instance in an Android app. This repository is the **client
side** of its cross-app API:

* `umapi/src/main/aidl/.../IUmApi.aidl` - the **contract**. This is the canonical copy: the tool
  builds its service implementation from exactly this file (it lives in this repository and is
  pulled into the tool as the `api` git submodule), so the two sides cannot drift apart.
* `umapi/src/main/java/.../client/` - a small **Java client** (`UmApiClient`, `UmApiJob`,
  `UmApiScript`, `UmApi`, `UmApiException`) that binds the service, runs scripts and parses every
  JSON reply. Usable from Java and Kotlin, no other dependency.
* `samples/` - a Kotlin sample and a .NET for Android client for a toolchain that cannot consume an
  AAR.
* `.github/workflows/` - CI that builds the AAR/classes jar and publishes them to two Maven
  repositories.

Everything is published as `com.undertalemodtool.avalonia:qiuutmtv5-api`:

| Artifact | What it is |
| --- | --- |
| `qiuutmtv5-api-<version>.aar` | The normal choice for Gradle/AGP: the AIDL-generated `IUmApi` classes **and** the Java client. |
| `qiuutmtv5-api-<version>-classes.jar` | The same compiled classes as a plain jar (`.NET for Android` bindings, non-Android tooling). |
| `qiuutmtv5-api-<version>-sources.jar` | Sources of the client. |

## Install

### 1. The repository

The artifacts are published both to **GitHub Packages** and, as a plain static Maven repository, to
**GitHub Pages**. GitHub Packages needs a token even to *read*, so for an open client the Pages
repository is the convenient one:

```kotlin
// settings.gradle.kts or build.gradle.kts
repositories {
    maven { url = uri("https://genouka.github.io/QiuUTMTv5-API/") }
}

dependencies {
    implementation("com.undertalemodtool.avalonia:qiuutmtv5-api:0.9.2.0")
}
```

<details>
<summary>Using GitHub Packages instead</summary>

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/Genouka/QiuUTMTv5-API")
        credentials {
            // A classic PAT with the read:packages scope; the built-in GITHUB_TOKEN only works
            // inside this repository's own workflows.
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}
```
</details>

### 2. The manifest

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- The tool's own permission. It is declared as signature|dangerous, which Android reads as
         signature-or-system: it is granted automatically to apps signed with the tool's
         certificate (and to preinstalled apps), and other apps cannot obtain it through a user
         prompt. -->
    <uses-permission android:name="com.undertalemodtool.avalonia.android.permission.RUN_SCRIPT" />

    <!-- Android 11+ package visibility: without this the bind fails as if the tool were missing. -->
    <queries>
        <package android:name="com.undertalemodtool.avalonia.android" />
    </queries>

    <application ... />
</manifest>
```

## Use

```java
UmApiClient client = new UmApiClient(context);

// connect() is safe on the main thread; every callback arrives there too. The blocking calls
// (runScript, loadDataFile, saveDataFile, awaitJob) belong on a worker thread - client.execute()
// is one, and the *Async methods use it for you.
client.connect(10_000, new UmApiClient.OnConnectedListener() {
    @Override public void onConnected(UmApiClient api) {
        api.runScriptAsync(
            "SetUMTConsoleText(\"hello\"); return 42;",   // csx source
            "demo.csx",                                   // name used in diagnostics
            false,                                        // assumeYes: answer ScriptQuestion with "no"
            chunk -> Log.i("demo", chunk),                // output, as it is produced
            (job, error) -> {
                if (error != null) Log.w("demo", error.getMessage());
                else Log.i("demo", job.state + " -> " + job.returnValue);
            });
    }

    @Override public void onConnectionFailed(String message) {
        Log.w("demo", message);
    }
});

// ... and when the client goes away:
client.close();
```

Kotlin, listing what the tool can run and then starting one of those scripts:

```kotlin
val client = UmApiClient(context)
client.connect(10_000) { api ->
    api.execute {
        val scripts = api.listScripts()
        val builtIn = scripts.first { it.builtIn }
        val job = api.awaitJob(api.startBuiltinScript(builtIn.relativePath, false)) { chunk ->
            Log.i(TAG, chunk)
        }
        Log.i(TAG, "finished: ${job.state}, output ${job.outputLength} chars")
    }
}
```

Loading and saving a data file (both block until the file is done, so they are asynchronous here):

```kotlin
client.loadDataFileAsync("/sdcard/QiuUTMTv5/data.win") { result, error ->
    if (error == null) Log.i(TAG, "loaded, warnings: " + result.optJSONArray("warnings"))
}
client.saveDataFileAsync("/sdcard/QiuUTMTv5/data.win") { _, error ->
    if (error != null) Log.w(TAG, error.message)
}
```

The full samples are [`samples/android-kotlin/UmApiSampleActivity.kt`](samples/android-kotlin/UmApiSampleActivity.kt)
and [`samples/dotnet-android/UmApiClient.cs`](samples/dotnet-android/UmApiClient.cs).

### Doing it without this SDK

The SDK is optional: the API is a normal bound `Service`, so a client can call it directly. Bind
with the action `com.undertalemodtool.avalonia.android.api.action.BIND_UM_API` (an explicit
component also works - `getApiInfo()` reports `serviceClass` / `serviceComponent`), and use the raw
interface:

* **Java/Kotlin with Gradle** - depend on the AAR, or copy `IUmApi.aidl` into
  `src/main/aidl/com/undertalemodtool/avalonia/android/api/` in your own project.
* **.NET for Android** - copy the same `.aidl` and add
  `<AndroidInterfaceDescription Include="Api\IUmApi.aidl" />`; the generated C# lives in namespace
  `Com.Undertalemodtool.Avalonia.Android.Api`. See `samples/dotnet-android/`.

## API reference

Every method returns a JSON document as a `String` (or a primitive where that is enough), so the
AIDL surface stays on plain types and the reply can grow without breaking clients. Every document
has `ok`; on failure it also has `error` (the text the tool would show the user). The SDK turns
`ok: false` into an `UmApiException`.

### `getApiInfo()`

The tool's status. Call it first: `ready` is false while the app is still starting (a bind can
arrive before the tool's own window exists).

```json
{
  "ok": true,
  "apiVersion": "1",
  "appVersion": "0.9.2.0",
  "packageName": "com.undertalemodtool.avalonia.android",
  "descriptor": "com.undertalemodtool.avalonia.android.api.IUmApi",
  "permission": "com.undertalemodtool.avalonia.android.permission.RUN_SCRIPT",
  "permissionLevel": "signature|dangerous",
  "serviceClass": "crc647dc9b11ba1885396.UmApiService",
  "serviceComponent": "com.undertalemodtool.avalonia.android/crc647dc9b11ba1885396.UmApiService",
  "bindAction": "com.undertalemodtool.avalonia.android.api.action.BIND_UM_API",
  "ready": true,
  "dataLoaded": true,
  "dataPath": "/sdcard/QiuUTMTv5/data.win",
  "projectName": "data.win",
  "runningJobId": null,
  "activeJobs": 0,
  "scriptRoots": [
    { "path": "/data/.../Scripts", "isBuiltIn": true, "exists": true },
    { "path": "/sdcard/QiuUTMTv5/Scripts", "isBuiltIn": false, "exists": false }
  ]
}
```

`apiVersion` is the version of the contract. A client that needs a feature should check it and fail
with a clear message instead of guessing.

### `listScripts()`

Every `csx` file the tool can run: the built-in ones plus the user's own scripts, looked up in
`scriptRoots`.

```json
{ "ok": true, "count": 2, "scripts": [
  { "name": "DumpAssets", "fileName": "DumpAssets.csx", "relativePath": "DumpAssets.csx",
    "root": "/data/.../Scripts", "isBuiltIn": true, "fullPath": "/data/.../Scripts/DumpAssets.csx" },
  { "name": "Mine", "fileName": "Mine.csx", "relativePath": "sub/Mine.csx",
    "root": "/sdcard/QiuUTMTv5/Scripts", "isBuiltIn": false,
    "fullPath": "/sdcard/QiuUTMTv5/Scripts/sub/Mine.csx" }
] }
```

### Starting something: `startScriptText` / `startScriptFile` / `startBuiltinScript`

```json
{ "ok": true, "jobId": "3f1c...", "state": "Queued" }
```

| Method | Argument |
| --- | --- |
| `startScriptText(scriptText, name, optionsJson)` | The csx source itself. At most **512 KB** - a Binder transaction is capped at 1 MB, so larger scripts have to go through a file. `name` is only used in diagnostics. |
| `startScriptFile(pathOrUri, optionsJson)` | A path, a `file://` URI, or a `content://` URI the tool may read (marshalled through its own `ContentResolver`). |
| `startBuiltinScript(relativePath, optionsJson)` | A `relativePath` from `listScripts()`, resolved inside the built-in script folders and `/sdcard/QiuUTMTv5/Scripts` only. |

`optionsJson` may be null; `{"assumeYes": true}` makes the host answer `ScriptQuestion`
confirmations with "yes" (the default is "no", which is the safe choice for an unattended caller).

### Jobs: `getJob` / `getJobOutput` / `cancelJob` / `waitForJob` / `listJobs` / `clearFinishedJobs`

A request becomes a **job** that the tool runs in the background. Jobs are never run in parallel:
scripts and data files share one queue, so a long script delays the next request.

```json
{
  "ok": true,
  "id": "3f1c...",
  "kind": "Script",
  "name": "demo.csx",
  "source": "inline:demo.csx",
  "state": "Succeeded",
  "queuedAt": 1759600000000, "startedAt": 1759600000012, "finishedAt": 1759600000987,
  "outputLength": 42,
  "progress": 1.0, "progressMaximum": 1.0,
  "progressMessage": "done", "progressStatus": "",
  "returnValue": "42", "returnType": "Int32",
  "consoleText": "hello",
  "error": null, "errorTitle": null,
  "cancelRequested": false,
  "assumeYes": false
}
```

`state` is one of `Queued`, `Running`, `Succeeded`, `Failed`, `Cancelled`; `kind` is one of
`Script`, `BuiltinScript`, `ScriptFile`, `LoadDataFile`, `SaveDataFile`. `error` carries the compile
or runtime failure text, and `consoleText` is whatever the script sent through
`SetUMTConsoleText`.

Output is read incrementally - the log can be megabytes long, so it is never returned in one piece:

```json
{ "ok": true, "id": "3f1c...", "state": "Running",
  "offset": 0, "nextOffset": 42, "eof": false, "text": "hello\n" }
```

Pass `nextOffset` into the next `getJobOutput(jobId, fromOffset)` call. Chunks are cut on character
boundaries (never inside a surrogate pair) and the total retained log per job is capped at 8 MB.

* `cancelJob(jobId)` - cooperative: a queued job is dropped immediately, a running script stops at
  its next progress/message call. A script that never reports anything runs to completion.
* `waitForJob(jobId, timeoutMillis)` - blocks the calling Binder thread until the job finished or
  the timeout (clamped to 0..600000 ms) elapsed; returns whether it finished. Poll with a short
  timeout (the SDK uses 1000 ms) so output can be streamed in between.
* `listJobs()` - metadata of every job the tool still remembers (no output text).
* `clearFinishedJobs()` - forgets the finished ones and returns how many were removed. At most 64
  jobs are kept; the oldest finished ones are dropped first.

### Data files: `loadDataFile` / `saveDataFile`

```json
{ "ok": true, "path": "/sdcard/QiuUTMTv5/data.win", "dataPath": "/sdcard/QiuUTMTv5/data.win",
  "importantWarnings": 0, "warnings": [] }
```

Both calls are synchronous: they return once the file has been read or written, which for a large
`data.win` takes seconds - call them from a worker thread. They bypass the tool's own file window
(the file is read directly, then the tool's data is replaced on its UI thread), which is what makes
them usable without a user in front of the app. `warnings` are the loader's warnings; some of them
mean parts of the file were skipped.

## Execution model

* A bound service starts the tool's process if it is not running (`BIND_AUTO_CREATE`); **no
  Activity is launched** and no window is opened. The tool's application object initialises its view
  model and scripting engine on its own, which is why `getApiInfo().ready` has to be checked before
  the first request.
* Requests are serialised: one script or data-file operation at a time, FIFO.
* A script runs on the tool's dedicated scripting thread (64 MB stack) with the tool's own
  `ScriptGlobals` API, so a script written for the tool's UI works unchanged - except that dialogs
  are captured instead of shown:
  * `ScriptMessage`, `ScriptWarning`, `ScriptError`, progress and console text go to the job's log.
  * `ScriptQuestion` is answered from `optionsJson.assumeYes` (default: no).
  * File/folder/input pickers and `RunUMTScript` of another file are handled without a dialog -
    pickers are reported as "cancelled" and logged.
* The tool's data is shared: a script sees whatever was loaded last, and `saveDataFile` writes the
  current state.

## Security

* The service is `exported`, but protected by
  `com.undertalemodtool.avalonia.android.permission.RUN_SCRIPT`, declared as
  `signature|dangerous`. Android reads that bitmask as **signature-or-system**
  (`protectionLevel=0x00000003` in the built APK): apps signed with the tool's certificate and
  preinstalled apps get it, and no other app can obtain it through a user prompt. A bind from an app
  without the permission is refused by the platform *and* re-checked by the service itself (calling
  uid, `checkCallingPermission`, signature comparison), so it also holds if the manifest is ever
  relaxed.
* To make the API usable by arbitrary apps, change `protectionLevel` to `dangerous` in the tool's
  `AndroidManifest.xml`: the user then grants it at install time. Every app holding it can run
  scripts, i.e. read and modify any data file the tool can reach - treat it as a powerful
  permission.
* A `content://` URI is only as safe as its grant: the tool opens it through its own resolver, so
  the caller still needs to hand over a readable URI.

## Limitations

* No Activity is started, so a caller cannot expect the tool's UI to appear, and nothing is
  displayed to the user while a script runs. Use `getJob().progressMessage` if you want to show
  progress in your own UI.
* `startScriptText` is limited to 512 KB per call (Binder). Use a file for anything larger.
* Cancellation is cooperative (see `cancelJob`).
* A script that blocks forever blocks the queue - there is no way to kill it.
* Data files are loaded/saved wholesale: the tool's "recompile all code" setting is honoured, but
  there is no partial or incremental save.
* The API is version 1 and may grow; removed methods would be a version bump (`apiVersion`).

## Building and publishing this repository

```bash
./gradlew :umapi:assembleRelease :umapi:classesJar     # AAR + classes jar in umapi/build/
./gradlew :umapi:publishAllPublicationsToPagesRepository   # file tree in umapi/build/maven-repo
./gradlew :umapi:publishAllPublicationsToGitHubPackagesRepository   # needs GITHUB_ACTOR/GITHUB_TOKEN
```

Requirements: JDK 17+ and the Android SDK (`ANDROID_HOME`, or `local.properties` with `sdk.dir`).
Gradle itself is provided by the wrapper (8.13); AGP 8.9.1.

**Releasing**: push a version tag; the workflow derives the version from it.

```bash
git tag v0.9.2.0 && git push origin v0.9.2.0
```

`publish.yml` then

1. publishes `com.undertalemodtool.avalonia:qiuutmtv5-api:<tag>` (AAR, sources jar, classes jar,
   POM, Gradle module metadata) to **GitHub Packages** with the built-in `GITHUB_TOKEN`, and
2. copies the same files to the **`gh-pages`** branch, which serves them as a public Maven
   repository at `https://genouka.github.io/QiuUTMTv5-API/`.

It can also be started from the Actions tab with an explicit version. Two notes: Pages has to be
enabled once for the repository (Settings → Pages → *Deploy from a branch* → `gh-pages` / root), and
a published version cannot be overwritten in GitHub Packages - publish a new one.

## Relationship to the tool

The tool (`Genouka/QiuUTMTv5`) consumes this repository as a git submodule named `api`:

```
UndertaleModTool/
├── api/                                  <- this repository
└── UndertaleModToolAvalonia.Android/
    ├── Api/UmApiService.cs, UmApiBinder.cs, ...   the service implementation
    └── UndertaleModToolAvalonia.Android.csproj    <AndroidInterfaceDescription Include="..\api\umapi\src\main\aidl\...\IUmApi.aidl" />
```

So: **the contract lives here**, the implementation lives there, and a client only ever needs this
repository.

## License

[Apache License 2.0](LICENSE).

---

## 中文速览

本仓库是 **QiuUTMTv5（安卓版 UndertaleModTool）跨应用 API 的 SDK**：其他应用可以绑定它的 Service，在工具进程里跑
`csx` 脚本、实时读取脚本输出、读写 `data.win` 数据文件。

* **契约**：[`IUmApi.aidl`](umapi/src/main/aidl/com/undertalemodtool/avalonia/android/api/IUmApi.aidl)
  是唯一权威副本，工具本体通过名为 `api` 的 git 子模块引用它，两边不会各写一份而对不上。
* **依赖**（推荐用 Pages 上的公开 Maven 仓库，不需要 token）：
  ```kotlin
  repositories { maven { url = uri("https://genouka.github.io/QiuUTMTv5-API/") } }
  dependencies { implementation("com.undertalemodtool.avalonia:qiuutmtv5-api:0.9.2.0") }
  ```
  也可用 GitHub Packages，但读取也需要 token。
* **产物**：AAR（Gradle/AGP 用）、`-classes.jar`（纯 jar）、`-sources.jar`。
* **接入清单**：声明 `<uses-permission android:name="com.undertalemodtool.avalonia.android.permission.RUN_SCRIPT" />`
  和 `<queries><package android:name="com.undertalemodtool.avalonia.android" /></queries>`
  （缺少 queries 在 Android 11+ 会绑定失败）。权限是 `signature|dangerous`，实际等于
  signature-or-system：只有用工具同一证书签名的应用（或预装应用）拿得到，普通第三方应用无法通过弹窗获取；
  若要让任意应用可用，需要把工具清单里的 `protectionLevel` 改成 `dangerous`（改为运行时授权）。
* **用法**：`UmApiClient` 绑定连接（`connect` 主线程安全，回调都在主线程；`runScript`、`loadDataFile`
  等阻塞调用请放工作线程，SDK 自带一个：`client.execute`），`startScriptText/File/BuiltinScript`
  返回 jobId，`getJobOutput` 增量读日志（用 `nextOffset` 续读），`waitForJob` 轮询，`cancelJob` 协作式取消。
  文件相关：`loadDataFile` / `saveDataFile` 是同步阻塞调用。
* **执行模型**：绑定即拉起工具进程，**不会打开任何界面**；所有请求串行执行，一次一个任务；脚本里的所有对话框都会被
  捕获进日志（`ScriptQuestion` 由 `optionsJson.assumeYes` 回答，默认否）。
* **发版**：打 tag（`git tag v0.9.2.0 && git push origin v0.9.2.0`）即可，`.github/workflows/publish.yml`
  会自动发布到 GitHub Packages 和 `gh-pages`（Pages 需在仓库设置里启用一次）。
* **许可证**：Apache License 2.0。
