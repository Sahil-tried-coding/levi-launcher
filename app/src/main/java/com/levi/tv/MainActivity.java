package com.levi.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Levi TV launcher home activity.
 *
 * The app list is discovered natively with PackageManager (TV launcher activities first, then ordinary
 * launcher activities). The WebView UI receives it as JSON through a single bridge object, and can only
 * start packages that were discovered, so the bridge cannot be used to launch arbitrary components.
 */
public class MainActivity extends Activity {

    private static final String TAG = "LeviLauncher";
    private static final int ICON_PX = 128;
    private static final int MAX_ICON_CACHE_ENTRIES = 300;

    /** Favourite apps are ordered first when present. Other launchable apps are still discovered and shown. */
    private static final String[] FAVOURITE_ORDER = {
            "com.google.android.youtube.tv",
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus",
            "com.spotify.tv.android"
    };

    private WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Icon PNG data URIs keyed by package. Written from the worker thread, invalidated from the UI thread. */
    private final Map<String, String> iconCache = new ConcurrentHashMap<>();

    /** Latest discovered apps, package -> entry. Only touched on the UI thread. */
    private final Map<String, LaunchableApp> apps = new LinkedHashMap<>();
    private String lastPayload;       // last JSON sent to the page, to avoid redundant re-renders
    private String pendingPayload;    // payload waiting for the page to finish loading
    private boolean pageReady;
    private int discoveryGeneration;  // UI thread only; stale worker results are dropped
    private boolean receiverRegistered;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);          // needed for the launcher UI only
        s.setDomStorageEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                Log.d(TAG, "[LeviWeb] " + message.message() + " (" + message.sourceId() + ":" + message.lineNumber() + ")");
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushToPage();
            }
        });
        web.addJavascriptInterface(new Bridge(), "Levi");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_PACKAGE_ADDED);
            f.addAction(Intent.ACTION_PACKAGE_REMOVED);
            f.addAction(Intent.ACTION_PACKAGE_CHANGED);
            f.addDataScheme("package");
            registerReceiver(packageReceiver, f);
            receiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            unregisterReceiver(packageReceiver);
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        immersive();
        web.onResume();
        // Returning from a streaming app (or after an install/uninstall) re-reads the list.
        refreshApps();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while the launcher is already running: return to Home with the last focus restored.
        evalJs("window.leviHome && window.leviHome();");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    /** Back never leaves the launcher. On a sub-screen it returns to Home, on Home it does nothing. */
    @Override
    public void onBackPressed() {
        evalJs("window.leviBack && window.leviBack();");
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        if (web != null) {
            web.stopLoading();
            web.setWebViewClient(new WebViewClient());
            web.removeJavascriptInterface("Levi");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void evalJs(String script) {
        if (web != null && pageReady) web.evaluateJavascript(script, null);
    }

    // ------------------------------------------------------------------ discovery

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getData() != null) {
                String pkg = intent.getData().getSchemeSpecificPart();
                if (pkg != null) iconCache.remove(pkg);
            }
            refreshApps();
        }
    };

    /** Starts a discovery pass on the worker thread. Newer passes supersede older ones. */
    private void refreshApps() {
        final int generation = ++discoveryGeneration;
        worker.execute(() -> {
            try {
                List<LaunchableApp> found = queryLaunchableApps();
                main.post(() -> applyDiscovery(generation, found, null));
            } catch (Throwable t) {
                Log.e(TAG, "App discovery failed", t);
                main.post(() -> applyDiscovery(generation, null, t.getClass().getSimpleName()));
            }
        });
    }

    private void applyDiscovery(int generation, List<LaunchableApp> found, String error) {
        if (generation != discoveryGeneration) return;   // a newer pass has been started
        if (found != null) {
            apps.clear();
            for (LaunchableApp a : found) apps.put(a.pkg, a);
        }
        JSONObject payload = new JSONObject();
        try {
            JSONArray arr = new JSONArray();
            if (found != null) {
                for (LaunchableApp a : apps.values()) {
                    JSONObject o = new JSONObject();
                    o.put("pkg", a.pkg);
                    o.put("label", a.label);
                    o.put("icon", a.icon == null ? JSONObject.NULL : a.icon);
                    arr.put(o);
                }
            }
            payload.put("apps", arr);
            if (error != null) payload.put("error", error);
        } catch (JSONException e) {
            Log.e(TAG, "Could not build app list", e);
            return;
        }
        pendingPayload = payload.toString();
        if (pageReady) pushToPage();
    }

    private void pushToPage() {
        if (web == null || pendingPayload == null || pendingPayload.equals(lastPayload)) return;
        lastPayload = pendingPayload;
        // JSONObject.quote produces a correctly escaped JavaScript string literal, so labels can never break out of it.
        web.evaluateJavascript("window.leviSetApps && window.leviSetApps(" + JSONObject.quote(pendingPayload) + ");", null);
    }

    /** Runs on the worker thread. Returns one entry per package, sorted for the launcher. */
    private List<LaunchableApp> queryLaunchableApps() {
        PackageManager pm = getPackageManager();
        String self = getPackageName();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, self, candidates);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, self, candidates);

        List<LaunchableApp> out = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            try {
                out.add(buildApp(pm, c));
            } catch (RuntimeException e) {
                // One broken package must not stop the whole list from loading.
                Log.w(TAG, "Skipping " + c.pkg, e);
            }
        }
        out.sort(Comparator
                .comparingInt((LaunchableApp a) -> favouriteRank(a.pkg))
                .thenComparing(a -> a.label.toLowerCase(java.util.Locale.ROOT)));
        return out;
    }

    private static void collect(PackageManager pm, String category, boolean leanback, String self,
                                Map<String, Candidate> out) {
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> results = pm.queryIntentActivities(probe, 0);
        if (results == null) return;
        for (ResolveInfo ri : results) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || !ai.enabled || ai.packageName == null || ai.packageName.equals(self)) continue;
            Candidate c = out.get(ai.packageName);
            if (c == null) {
                c = new Candidate(ai.packageName);
                out.put(ai.packageName, c);
            }
            if (leanback) {
                if (c.leanbackClass == null) c.leanbackClass = ai.name;
                c.info = ri;                       // TV entry gives the label and icon when available
            } else {
                if (c.launcherClass == null) c.launcherClass = ai.name;
                if (c.info == null) c.info = ri;
            }
        }
    }

    private LaunchableApp buildApp(PackageManager pm, Candidate c) {
        String label = String.valueOf(c.info.loadLabel(pm)).trim();
        if (label.isEmpty()) label = c.pkg;
        String icon = iconCache.get(c.pkg);
        if (icon == null) {
            icon = drawableToDataUri(c.info.loadIcon(pm));
            if (icon != null && iconCache.size() < MAX_ICON_CACHE_ENTRIES) iconCache.put(c.pkg, icon);
        }
        return new LaunchableApp(c.pkg, label, icon, c.leanbackClass, c.launcherClass);
    }

    private static String drawableToDataUri(Drawable d) {
        if (d == null) return null;
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            d.setBounds(0, 0, ICON_PX, ICON_PX);
            d.draw(canvas);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (RuntimeException e) {
            Log.w(TAG, "Icon conversion failed", e);
            return null;   // the UI shows the app's initial instead
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    private static int favouriteRank(String pkg) {
        for (int i = 0; i < FAVOURITE_ORDER.length; i++) {
            if (FAVOURITE_ORDER[i].equals(pkg)) return i;
        }
        return Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------ launching

    private void launch(String pkg) {
        LaunchableApp app = apps.get(pkg);
        if (app == null) {
            toast("That app is no longer installed");
            refreshApps();
            return;
        }
        // Prefer the TV launcher activity, then the ordinary launcher activity.
        if (tryStart(app.pkg, app.leanbackClass, Intent.CATEGORY_LEANBACK_LAUNCHER)) return;
        if (tryStart(app.pkg, app.launcherClass, Intent.CATEGORY_LAUNCHER)) return;
        toast("Could not open " + app.label);
    }

    private boolean tryStart(String pkg, String className, String category) {
        if (className == null) return false;
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(category)
                .setComponent(new ComponentName(pkg, className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "Could not start " + pkg + "/" + className, e);
            return false;
        }
    }

    private void startSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            toast("Settings are not available");
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /** Methods called from the page. They run on a WebView thread, so they hop to the UI thread. */
    class Bridge {
        @JavascriptInterface
        public void open(String pkg) {
            main.post(() -> launch(pkg));
        }

        @JavascriptInterface
        public void openSettings() {
            main.post(MainActivity.this::startSettings);
        }

        @JavascriptInterface
        public void refresh() {
            main.post(MainActivity.this::refreshApps);
        }
    }

    // ------------------------------------------------------------------ data

    private static final class Candidate {
        final String pkg;
        String leanbackClass;
        String launcherClass;
        ResolveInfo info;

        Candidate(String pkg) {
            this.pkg = pkg;
        }
    }

    private static final class LaunchableApp {
        final String pkg;
        final String label;
        final String icon;
        final String leanbackClass;
        final String launcherClass;

        LaunchableApp(String pkg, String label, String icon, String leanbackClass, String launcherClass) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
            this.leanbackClass = leanbackClass;
            this.launcherClass = launcherClass;
        }
    }
}
