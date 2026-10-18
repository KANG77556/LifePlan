package kr.lifeplan.app;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure validation helpers shared by the updater and CI smoke tests. */
public final class UpdateUtil {
    private UpdateUtil() {}

    private static final Pattern SEMVER = Pattern.compile("^[0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8}$");
    private static final Pattern RELEASE_APK = Pattern.compile(
        "^/KANG77556/LifePlan/releases/download/v([0-9]{1,8}\\.[0-9]{1,8}\\.[0-9]{1,8})/LifePlan-release\\.apk$");
    private static final Pattern DIGEST = Pattern.compile("^sha256:[a-fA-F0-9]{64}$");

    public static int compareVersions(String a, String b) {
        if (a == null || b == null || !SEMVER.matcher(a).matches() || !SEMVER.matcher(b).matches()) {
            throw new IllegalArgumentException("Invalid version");
        }
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int lhs = Integer.parseInt(x[i]), rhs = Integer.parseInt(y[i]);
            if (lhs != rhs) return Integer.compare(lhs, rhs);
        }
        return 0;
    }

    /** Installed app is the target version or a more recent version. */
    public static boolean isPendingInstalled(String installedVersion, String pendingVersion) {
        return compareVersions(installedVersion, pendingVersion) >= 0;
    }

    /** Do not repeatedly open Android's installer after a user has dismissed it. */
    public static boolean shouldAutoOpenInstaller(boolean foreground, boolean alreadyOffered) {
        return foreground && !alreadyOffered;
    }

    public static String versionFromTrustedUrl(String raw) {
        if (raw == null) return null;
        try {
            URI u = new URI(raw);
            if (!"https".equalsIgnoreCase(u.getScheme())
                    || !"github.com".equalsIgnoreCase(u.getHost())
                    || u.getPort() != -1 || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null
                    || u.getRawPath() == null) return null;
            Matcher m = RELEASE_APK.matcher(u.getRawPath());
            return m.matches() ? m.group(1) : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String githubSha256(String digest) {
        if (digest == null || !DIGEST.matcher(digest).matches()) return null;
        return digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
    }
}
