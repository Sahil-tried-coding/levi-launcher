package com.levi.tv;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public class MainActivity extends Activity {
    private static final String TAG = "LeviLauncher";
    private WebView web;
    private boolean webViewDestroyed = false;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        web = new WebView(this);
        configureWebView(web);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    private void configureWebView(WebView w) {
        w.setBackgroundColor(Color.BLACK);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        // The page sizes itself with JavaScript (fit() in index.html) from
        // innerWidth/innerHeight, so honour the viewport meta tag and keep
        // WebView zoom/text scaling from double-scaling or reflowing it.
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        w.setWebViewClient(new LeviWebViewClient());
        w.setWebChromeClient(new LeviWebChromeClient());
        w.addJavascriptInterface(new Bridge(this), "Levi");
    }

    private void recreateWebView() {
        if (webViewDestroyed) return;
        Log.w(TAG, "Recreating WebView after renderer loss");
        try {
            if (web != null) {
                web.stopLoading();
                if (web.getParent() instanceof ViewGroup) {
                    ((ViewGroup) web.getParent()).removeView(web);
                }
                web.removeJavascriptInterface("Levi");
                web.destroy();
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to destroy crashed WebView", e);
        }
        web = new WebView(this);
        configureWebView(web);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        web.requestFocus();
    }

    private class LeviWebViewClient extends WebViewClient {
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                Log.e(TAG, "WebView load error: " + error.getDescription() + " url=" + request.getUrl());
            }
            super.onReceivedError(view, request, error);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
            if (request.isForMainFrame()) {
                Log.e(TAG, "WebView HTTP error: " + errorResponse.getStatusCode() + " url=" + request.getUrl());
            }
            super.onReceivedHttpError(view, request, errorResponse);
        }

        @TargetApi(Build.VERSION_CODES.O)
        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            Log.e(TAG, "WebView renderer gone, didCrash=" + detail.didCrash());
            recreateWebView();
            return true;
        }
    }

    private class LeviWebChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            String text = "[LeviWeb] " + message.message()
                    + " (" + message.sourceId() + ":" + message.lineNumber() + ")";
            switch (message.messageLevel()) {
                case ERROR:
                    Log.e(TAG, text);
                    break;
                case WARNING:
                    Log.w(TAG, text);
                    break;
                default:
                    Log.d(TAG, text);
                    break;
            }
            return true;
        }
    }

    private static class Bridge {
        private final WeakReference<MainActivity> ref;

        Bridge(MainActivity activity) {
            ref = new WeakReference<>(activity);
        }

        @JavascriptInterface
        public void open(String pkg) {
            MainActivity activity = ref.get();
            if (activity == null || pkg == null || pkg.isEmpty()) return;
            activity.runOnUiThread(() -> activity.launch(pkg));
        }
    }

    private void launch(String pkg) {
        try {
            if ("settings".equals(pkg)) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) {
                Toast.makeText(this, "App not installed: " + pkg, Toast.LENGTH_LONG).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "Cannot open app: " + pkg, e);
            Toast.makeText(this, "Cannot open app", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app: " + pkg, e);
            Toast.makeText(this, "Cannot open app", Toast.LENGTH_SHORT).show();
        }
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override protected void onResume() {
        super.onResume();
        immersive();
        if (web != null) {
            web.onResume();
            web.resumeTimers();
        }
    }

    @Override protected void onPause() {
        super.onPause();
        if (web != null) {
            web.onPause();
            web.pauseTimers();
        }
    }

    @Override protected void onDestroy() {
        webViewDestroyed = true;
        if (web != null) {
            try {
                web.stopLoading();
                if (web.getParent() instanceof ViewGroup) {
                    ((ViewGroup) web.getParent()).removeView(web);
                }
                web.removeJavascriptInterface("Levi");
                web.destroy();
            } catch (Exception e) {
                Log.w(TAG, "WebView destroy failed", e);
            }
            web = null;
        }
        super.onDestroy();
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        if (f) {
            immersive();
            if (web != null) web.requestFocus();
        }
    }

    // Home screen: Back does nothing, Home button returns here.
    @Override public void onBackPressed() { }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (web != null) {
            web.evaluateJavascript("document.getElementById('card').focus()", null);
        }
    }
}
