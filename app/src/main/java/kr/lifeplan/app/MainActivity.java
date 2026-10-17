package kr.lifeplan.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity {
    private WebView web;
    private DownloadManager downloads;
    private long downloadId = -1L;
    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long completed = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
            if (completed != downloadId) return;
            downloadId = -1L;
            installDownloaded(completed);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        downloads = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDefaultTextEncodingName("UTF-8");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("https://")) {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface public void syncReminders(String json) { runOnUiThread(() -> ReminderReceiver.schedule(MainActivity.this,json)); }
            @JavascriptInterface public void requestReminderPermission() { runOnUiThread(() -> { if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 405); }); }
            @JavascriptInterface public void openInstaller(String url) {
                runOnUiThread(() -> downloadUpdate(url));
            }
        }, "LifePlanNative");
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
    }

    private boolean isTrustedApk(String value) {
        try {
            Uri uri = Uri.parse(value);
            return "https".equals(uri.getScheme()) && "github.com".equals(uri.getHost())
                && uri.getPath() != null
                && uri.getPath().matches("/KANG77556/LifePlan/releases/download/v[0-9]+\\.[0-9]+\\.[0-9]+/LifePlan-release\\.apk");
        } catch (Exception e) { return false; }
    }

    private void downloadUpdate(String url) {
        if (!isTrustedApk(url)) {
            Toast.makeText(this, "유효하지 않은 업데이트 주소입니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (downloadId != -1L) {
            Toast.makeText(this, "업데이트 파일을 다운로드하고 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle("라이프플랜 업데이트");
            req.setDescription("APK 다운로드 중");
            req.setMimeType("application/vnd.android.package-archive");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "LifePlan-update.apk");
            downloadId = downloads.enqueue(req);
            Toast.makeText(this, "업데이트 다운로드를 시작했습니다.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            downloadId = -1L;
            Toast.makeText(this, "다운로드 시작 실패: 네트워크와 다운로드 관리자를 확인하세요.", Toast.LENGTH_LONG).show();
        }
    }

    private void installDownloaded(long id) {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        try (android.database.Cursor cursor = downloads.query(query)) {
            if (cursor == null || !cursor.moveToFirst()) return;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) {
                Toast.makeText(this, "APK 다운로드 실패. 다시 시도하세요.", Toast.LENGTH_LONG).show();
                return;
            }
        }
        Uri apk = downloads.getUriForDownloadedFile(id);
        if (apk == null) {
            Toast.makeText(this, "다운로드 파일을 찾지 못했습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "라이프플랜의 알 수 없는 앱 설치를 허용한 뒤 다시 업데이트하세요.", Toast.LENGTH_LONG).show();
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName()));
            startActivity(settings);
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(apk, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(install);
        } catch (Exception e) {
            Toast.makeText(this, "설치 화면을 열지 못했습니다. 다운로드에서 APK를 설치하세요.", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        unregisterReceiver(downloadReceiver);
        super.onDestroy();
    }
    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }
}
