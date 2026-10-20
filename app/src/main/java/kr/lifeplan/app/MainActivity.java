package kr.lifeplan.app;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Insets;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.ValueCallback;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private ValueCallback<Uri[]> pendingFileSelection;
    private static final int REQUEST_BACKUP_FILE = 406;
    private AppUpdater updater;
    private String pendingWidgetPage;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent()!=null && LifePlanWidget.ACTION_OPEN.equals(getIntent().getAction())) pendingWidgetPage=getIntent().getStringExtra("page");
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (url.startsWith("file:///android_asset/")) { updater.onWebReady(); openWidgetPage(); }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("https://")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFileSelection != null) pendingFileSelection.onReceiveValue(null);
                pendingFileSelection = callback;
                try {
                    Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    choose.addCategory(Intent.CATEGORY_OPENABLE);
                    choose.setType("application/json");
                    choose.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(choose, REQUEST_BACKUP_FILE);
                    return true;
                } catch (Exception e) {
                    pendingFileSelection = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this, "파일 선택기를 열 수 없습니다.", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void syncWidgets(String json) { LifePlanWidget.sync(MainActivity.this, json); }
            @JavascriptInterface public void syncScheduleAlarms(String json) { ScheduleAlertReceiver.sync(MainActivity.this,json); }
            @JavascriptInterface public void syncReminders(String json) { runOnUiThread(() -> ReminderReceiver.schedule(MainActivity.this,json)); }
            @JavascriptInterface public void requestReminderPermission() { runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 405); }); }
            @JavascriptInterface public void openInstaller(String url) { runOnUiThread(() -> updater.checkForUpdates(false)); }
            @JavascriptInterface public void checkForUpdates() { runOnUiThread(() -> updater.checkForUpdates(false)); }
            @JavascriptInterface public void retryUpdateDownload() { runOnUiThread(() -> updater.retryDownload()); }
            @JavascriptInterface public void setAutoUpdate(boolean value) { runOnUiThread(() -> updater.setAutoEnabled(value)); }
        }, "LifePlanNative");
        // Android 15 (targetSdk 35) draws content behind the system status bar.
        // Keep WebView content below the status-bar/cutout inset, with a matching
        // blue scrim behind the bar rather than relying on WebView CSS env().
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(84, 124, 240));
        FrameLayout.LayoutParams webLayout = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(web, webLayout);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
                Insets safe = windowInsets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) web.getLayoutParams();
                if (lp.topMargin != safe.top) {
                    lp.topMargin = safe.top;
                    web.setLayoutParams(lp);
                }
                return windowInsets;
            });
        }
        updater = new AppUpdater(this, web);
        updater.register();
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 35) root.requestApplyInsets();
        web.loadUrl("file:///android_asset/index.html");
    }

    private void openWidgetPage() {
        String page=pendingWidgetPage;
        if (!"daily".equals(page) && !"recurring".equals(page)) return;
        pendingWidgetPage=null;
        if (web!=null) web.evaluateJavascript("window.goTo && window.goTo('"+page+"');", null);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (LifePlanWidget.ACTION_OPEN.equals(intent.getAction())) {
            pendingWidgetPage=intent.getStringExtra("page");
            openWidgetPage();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BACKUP_FILE && pendingFileSelection != null) {
            Uri[] uris = resultCode == RESULT_OK && data != null && data.getData() != null
                ? new Uri[]{data.getData()} : null;
            pendingFileSelection.onReceiveValue(uris);
            pendingFileSelection = null;
        }
    }

    @Override protected void onResume() { super.onResume(); if (updater != null) updater.onResume(); }
    @Override protected void onPause() { if (updater != null) updater.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        if (updater != null) updater.onDestroy();
        if (pendingFileSelection != null) {
            pendingFileSelection.onReceiveValue(null);
            pendingFileSelection = null;
        }
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
