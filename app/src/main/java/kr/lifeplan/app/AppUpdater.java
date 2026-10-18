package kr.lifeplan.app;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public signed GitHub Release -> background download -> verified APK -> Android installer.
 * Android intentionally requires the person to confirm the final system install prompt.
 */
public final class AppUpdater {
    private static final String RELEASES_API =
        "https://api.github.com/repos/KANG77556/LifePlan/releases?per_page=20";
    private static final String MIME_APK = "application/vnd.android.package-archive";
    private static final String P_AUTO = "auto";
    private static final String P_ID = "download_id";
    private static final String P_VERSION = "pending_version";
    private static final String P_SHA = "expected_sha";
    private static final String P_FILE = "file_name";
    private static final String P_INSTALL_OFFERED = "installer_offered";

    private final Activity activity;
    private final WebView web;
    private final DownloadManager manager;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean verifying = new AtomicBoolean(false);
    private final Handler progressHandler = new Handler(Looper.getMainLooper());
    private final Runnable pollProgress = new Runnable() {
        @Override public void run() {
            if (destroyed || !foreground) return;
            if (prefs.getLong(P_ID, -1L) >= 0) inspectPending();
            progressHandler.postDelayed(this, 3000);
        }
    };
    private boolean checking;
    private boolean foreground;
    private boolean registered;
    private boolean destroyed;
    private boolean webReady;
    private boolean installerLaunched;
    private boolean autoCheckedThisSession;
    private boolean permissionPageOpened;
    private String lastStatus = "자동 업데이트 준비됨";

    private static final class Release {
        final String version;
        final String url;
        final String sha;
        Release(String version, String url, String sha) {
            this.version = version;
            this.url = url;
            this.sha = sha;
        }
    }

    private final BroadcastReceiver completed = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    == prefs.getLong(P_ID, -1L)) inspectPending();
        }
    };

    public AppUpdater(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
        manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        prefs = activity.getSharedPreferences("lifeplan_updater", Context.MODE_PRIVATE);
    }

    public void register() {
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // DownloadProvider can be a privileged system app outside the system UID.
        // Never trust broadcast extras alone: query by our persisted download ID and
        // verify the binary digest + Android signing certificate before installing.
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(completed, filter, Context.RECEIVER_EXPORTED);
        } else {
            activity.registerReceiver(completed, filter);
        }
        registered = true;
    }

    public void onResume() {
        foreground = true;
        progressHandler.removeCallbacks(pollProgress);
        progressHandler.postDelayed(pollProgress, 3000);
        if (pendingAlreadyInstalled()) return;
        inspectPending();
    }

    private boolean pendingAlreadyInstalled() {
        long id = prefs.getLong(P_ID, -1L);
        String target = prefs.getString(P_VERSION, "");
        if (id < 0 || target.isEmpty()) return false;
        try {
            if (UpdateUtil.isPendingInstalled(installedVersion(), target)) {
                clearPending(id);
                status("업데이트 설치 완료. 최신 버전을 사용 중입니다.");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public void onPause() {
        foreground = false;
        progressHandler.removeCallbacks(pollProgress);
    }

    public void onDestroy() {
        progressHandler.removeCallbacks(pollProgress);
        destroyed = true;
        foreground = false;
        if (registered) activity.unregisterReceiver(completed);
        io.shutdownNow();
    }

    public void onWebReady() {
        webReady = true;
        web.evaluateJavascript("window.LifePlanAutoUpdateSetting && window.LifePlanAutoUpdateSetting("
            + autoEnabled() + ");", null);
        status(lastStatus);
        if (pendingAlreadyInstalled()) status("업데이트 설치 완료.");
        inspectPending();
        if (autoEnabled() && !autoCheckedThisSession && prefs.getLong(P_ID, -1L) < 0) { autoCheckedThisSession = true; checkForUpdates(true); }
    }

    public boolean autoEnabled() {
        return prefs.getBoolean(P_AUTO, true);
    }

    public void setAutoEnabled(boolean enabled) {
        prefs.edit().putBoolean(P_AUTO, enabled).apply();
        status(enabled ? "자동 업데이트가 켜졌습니다." : "자동 업데이트가 꺼졌습니다. 수동 확인은 계속 가능합니다.");
        if (enabled && prefs.getLong(P_ID, -1L) < 0) { autoCheckedThisSession = true; checkForUpdates(true); }
    }

    public void checkForUpdates(boolean automatic) {
        if (destroyed || (automatic && !autoEnabled())) return;
        pendingAlreadyInstalled();
        if (prefs.getLong(P_ID, -1L) >= 0) {
            if (!automatic) {
                installerLaunched = false;
                prefs.edit().putBoolean(P_INSTALL_OFFERED, false).apply(); // Explicit retry only.
            }
            inspectPending();
            return;
        }
        if (checking) {
            status("업데이트 확인 중입니다.");
            return;
        }
        checking = true;
        status("최신 버전을 확인하고 있습니다…");
        io.execute(() -> {
            try {
                Release latest = fetchLatestRelease();
                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    checking = false;
                    if (automatic && !autoEnabled()) {
                        status("자동 업데이트가 꺼져 있어 다운로드를 시작하지 않았습니다.");
                    } else if (latest == null) {
                        status("현재 최신 버전을 사용하고 있습니다.");
                    } else {
                        beginDownload(latest);
                    }
                });
            } catch (Exception error) {
                activity.runOnUiThread(() -> {
                    if (destroyed) return;
                    checking = false;
                    status("업데이트 확인에 실패했습니다. 인터넷 연결을 확인한 후 다시 시도하세요.");
                });
            }
        });
    }

    private Release fetchLatestRelease() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "LifePlan-Android-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("GitHub API " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    if (out.size() + n > 2097152) throw new IllegalStateException("Release data too large");
                    out.write(buf, 0, n);
                }
            }
            JSONArray list = new JSONArray(out.toString(StandardCharsets.UTF_8.name()));
            String installed = installedVersion();
            Release best = null;
            for (int i = 0; i < list.length(); i++) {
                JSONObject entry = list.optJSONObject(i);
                if (entry == null || entry.optBoolean("draft") || entry.optBoolean("prerelease")) continue;
                String tag = entry.optString("tag_name", "");
                if (!tag.matches("v[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}")) continue;
                String candidate = tag.substring(1);
                if (UpdateUtil.compareVersions(candidate, installed) <= 0) continue;
                JSONArray assets = entry.optJSONArray("assets");
                if (assets == null) continue;
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.optJSONObject(j);
                    if (asset == null || !"LifePlan-release.apk".equals(asset.optString("name"))) continue;
                    String url = asset.optString("browser_download_url", "");
                    String digest = UpdateUtil.githubSha256(asset.optString("digest", ""));
                    if (!candidate.equals(UpdateUtil.versionFromTrustedUrl(url)) || digest == null) continue;
                    if (best == null || UpdateUtil.compareVersions(candidate, best.version) > 0) {
                        best = new Release(candidate, url, digest);
                    }
                }
            }
            return best;
        } finally {
            c.disconnect();
        }
    }

    private String installedVersion() throws Exception {
        PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
        return info.versionName;
    }

    private void beginDownload(Release release) {
        if (destroyed) return;
        if (prefs.getLong(P_ID, -1L) >= 0) {
            inspectPending();
            return;
        }
        try {
            File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) throw new IllegalStateException("Private download folder unavailable");
            String fileName = "LifePlan-update-v" + release.version + ".apk";
            File file = new File(dir, fileName);
            if (file.exists() && !file.delete()) throw new IllegalStateException("Cannot remove older download");
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.url));
            request.setTitle("라이프플랜 v" + release.version + " 업데이트");
            request.setDescription("업데이트 APK 자동 다운로드 중");
            request.setMimeType(MIME_APK);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, fileName);
            long id = manager.enqueue(request);
            prefs.edit().putLong(P_ID, id).putString(P_VERSION, release.version)
                .putString(P_SHA, release.sha).putString(P_FILE, fileName)
                .putBoolean(P_INSTALL_OFFERED, false).apply();
            installerLaunched = false;
            permissionPageOpened = false;
            status("v" + release.version + " 다운로드 중… 완료되면 설치 화면을 자동으로 엽니다.");
            Toast.makeText(activity, "라이프플랜 v" + release.version + " 자동 다운로드 시작", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            status("자동 다운로드를 시작하지 못했습니다. 저장공간과 다운로드 관리자를 확인하세요.");
        }
    }

    private void inspectPending() {
        final long id = prefs.getLong(P_ID, -1L);
        if (destroyed || id < 0 || !verifying.compareAndSet(false, true)) return;
        io.execute(() -> {
            String downloadProgress = null;
            boolean ready = false;
            boolean failed = false;
            String version = prefs.getString(P_VERSION, "");
            try {
                DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
                int state;
                try (Cursor c = manager.query(query)) {
                    if (c == null || !c.moveToFirst()) throw new IllegalStateException("Download no longer available");
                    state = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (state == DownloadManager.STATUS_PENDING || state == DownloadManager.STATUS_RUNNING
                            || state == DownloadManager.STATUS_PAUSED) {
                        long current = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                        long total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                        String amount = total > 0 ? (Math.min(100, Math.max(0, 100 * current / total)) + "%") : "진행 중";
                        downloadProgress = "v" + version + " 다운로드 " + amount
                            + (state == DownloadManager.STATUS_PAUSED ? " · 네트워크 대기 중" : "");
                    }
                }
                if (state == DownloadManager.STATUS_FAILED) throw new IllegalStateException("Download failed");
                if (state == DownloadManager.STATUS_SUCCESSFUL) {
                    Uri uri = manager.getUriForDownloadedFile(id);
                    if (uri == null) throw new IllegalStateException("Downloaded APK not available");
                    String expected = prefs.getString(P_SHA, "");
                    if (!expected.equalsIgnoreCase(sha256(uri))) {
                        throw new SecurityException("APK checksum mismatch");
                    }
                    File folder = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                    if (folder == null) throw new IllegalStateException("Download folder unavailable");
                    File apk = new File(folder, prefs.getString(P_FILE, ""));
                    verifyApkSignature(apk, version);
                    ready = true;
                }
            } catch (Exception e) {
                failed = true;
            }
            boolean ok = ready, error = failed;
            String progress = downloadProgress;
            activity.runOnUiThread(() -> {
                verifying.set(false);
                if (destroyed || id != prefs.getLong(P_ID, -1L)) return;
                if (error) {
                    clearPending(id);
                    status("업데이트 파일 확인에 실패했습니다. 재시도하려면 '최신 버전 확인'을 누르세요.");
                } else if (ok) {
                    if (UpdateUtil.shouldAutoOpenInstaller(foreground,
                            installerLaunched || prefs.getBoolean(P_INSTALL_OFFERED, false))) {
                        showInstaller(id, version);
                    } else if (!foreground) {
                        status("업데이트 다운로드 완료. 앱을 다시 열면 설치 화면을 표시합니다.");
                    } else if (prefs.getBoolean(P_INSTALL_OFFERED, false)) {
                        status("v" + version + " APK 준비됨. 설치를 다시 진행하려면 최신 버전 확인을 누르세요.");
                    }
                } else if (progress != null) {
                    status(progress);
                }
            });
        });
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("Cannot open APK");
            byte[] bytes = new byte[16384];
            int n;
            while ((n = in.read(bytes)) >= 0) if (n > 0) digest.update(bytes, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    private void verifyApkSignature(File apk, String version) throws Exception {
        if (!apk.isFile() || apk.length() < 1024) throw new SecurityException("APK missing");
        PackageManager pm = activity.getPackageManager();
        int flag = Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo existing = pm.getPackageInfo(activity.getPackageName(), flag);
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flag);
        if (archive == null || !activity.getPackageName().equals(archive.packageName)
                || !version.equals(archive.versionName)
                || (Build.VERSION.SDK_INT >= 28 ? archive.getLongVersionCode() <= existing.getLongVersionCode() : archive.versionCode <= existing.versionCode)) {
            throw new SecurityException("APK package or version mismatch");
        }
        Signature[] current, candidate;
        if (Build.VERSION.SDK_INT >= 28) {
            current = existing.signingInfo == null ? null : existing.signingInfo.getApkContentsSigners();
            candidate = archive.signingInfo == null ? null : archive.signingInfo.getApkContentsSigners();
        } else {
            current = existing.signatures;
            candidate = archive.signatures;
        }
        if (current == null || current.length == 0 || candidate == null || candidate.length == 0
                || !Arrays.equals(current, candidate)) {
            throw new SecurityException("APK signing key differs from installed app");
        }
    }

    private void showInstaller(long id, String version) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            status("APK가 준비되었습니다. 최초 1회 '알 수 없는 앱 설치 허용'이 필요합니다.");
            if (!permissionPageOpened && foreground) {
                permissionPageOpened = true;
                try {
                    activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
                } catch (Exception e) {
                    status("휴대전화 설정에서 라이프플랜의 앱 설치 권한을 허용해 주세요.");
                }
            }
            return;
        }
        Uri content = manager.getUriForDownloadedFile(id);
        if (content == null) {
            clearPending(id);
            status("다운로드한 파일을 열지 못했습니다.");
            return;
        }
        try {
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(content, MIME_APK);
            install.setClipData(ClipData.newRawUri("라이프플랜 업데이트", content));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            installerLaunched = true;
            status("v" + version + " 설치 확인 화면을 열었습니다. Android의 '업데이트/설치'를 눌러 주세요.");
            activity.startActivity(install);
            prefs.edit().putBoolean(P_INSTALL_OFFERED, true).apply();
        } catch (Exception e) {
            installerLaunched = false;
            status("설치 화면을 열지 못했습니다. 설정의 '최신 버전 확인'으로 다시 시도하세요.");
        }
    }

    public void retryDownload() {
        if (destroyed) return;
        long id = prefs.getLong(P_ID, -1L);
        if (id >= 0) clearPending(id);
        status("기존 다운로드 상태를 초기화하고 새 버전을 확인합니다…");
        checkForUpdates(false);
    }

    private void clearPending(long id) {
        try { manager.remove(id); } catch (Exception ignored) {}
        prefs.edit().remove(P_ID).remove(P_SHA).remove(P_VERSION).remove(P_FILE)
            .remove(P_INSTALL_OFFERED).apply();
        installerLaunched = false;
    }

    private void status(String message) {
        lastStatus = message;
        if (destroyed || !webReady) return;
        web.evaluateJavascript("window.LifePlanUpdateStatus && window.LifePlanUpdateStatus("
            + JSONObject.quote(message) + ");", null);
    }
}
