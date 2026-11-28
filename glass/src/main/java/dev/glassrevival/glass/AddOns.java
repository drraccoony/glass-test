package dev.glassrevival.glass;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;
import android.util.Log;
import dev.glassrevival.protocol.Protocol;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Add-on apps (Navigation, Recipes, Airports) for the phone's Apps tab: lists what's
 * installed, and installs or removes them on request.
 *
 * <p>Installing and removing need INSTALL_PACKAGES and DELETE_PACKAGES, which only the
 * copy in /system/priv-app gets (glasslink.sh install-glass-system). Without them the phone
 * still sees the list, with canInstall false.
 *
 * <p>Only dev.glassrevival.* packages signed with GlassLink's own key are listed, installed
 * or removed, and never GlassLink itself.
 */
final class AddOns {

    interface Sender {
        boolean send(JSONObject msg);
    }

    private static final String TAG = "GlassLink";

    private final Context context;
    private final Sender sender;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    AddOns(Context context, Sender sender) {
        this.context = context.getApplicationContext();
        this.sender = sender;
    }

    boolean canInstall() {
        return context.checkCallingOrSelfPermission("android.permission.INSTALL_PACKAGES")
                == PackageManager.PERMISSION_GRANTED
                && context.checkCallingOrSelfPermission("android.permission.DELETE_PACKAGES")
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Tells the phone what's installed. */
    void sendList() {
        worker.execute(() -> {
            try {
                sender.send(list());
            } catch (JSONException e) {
                Log.w(TAG, "Add-on list failed", e);
            }
        });
    }

    /** app_install or app_remove from the phone. */
    void handle(JSONObject msg) {
        final String type = msg.optString(Protocol.F_TYPE);
        final String pkg = msg.optString(Protocol.F_PKG);
        final String data = msg.optString(Protocol.F_DATA, null);
        worker.execute(() -> {
            String op = Protocol.APP_INSTALL.equals(type) ? "install" : "remove";
            String error;
            try {
                error = Protocol.APP_INSTALL.equals(type) ? install(pkg, data) : remove(pkg);
            } catch (IOException | RuntimeException e) {
                Log.w(TAG, "Add-on " + op + " failed", e);
                error = "Glass couldn't " + op + " it";
            }
            Log.i(TAG, "Add-on " + op + " " + pkg + ": " + (error == null ? "done" : error));
            try {
                JSONObject result = new JSONObject()
                        .put(Protocol.F_TYPE, Protocol.APP_RESULT)
                        .put(Protocol.F_PKG, pkg)
                        .put(Protocol.F_OP, op)
                        .put(Protocol.F_OK, error == null);
                if (error != null) result.put(Protocol.F_ERROR, error);
                sender.send(result);
                sender.send(list());
            } catch (JSONException ignored) {
                // cannot happen
            }
        });
    }

    private JSONObject list() throws JSONException {
        PackageManager pm = context.getPackageManager();
        JSONArray items = new JSONArray();
        for (PackageInfo p : pm.getInstalledPackages(PackageManager.GET_SIGNATURES)) {
            if (!isAddOnName(p.packageName) || !sameKey(p.signatures)) continue;
            String sha = sha1(new File(p.applicationInfo.sourceDir));
            items.put(new JSONObject()
                    .put(Protocol.F_PKG, p.packageName)
                    .put(Protocol.F_VERSION, p.versionName == null ? "" : p.versionName)
                    .put(Protocol.F_SHA, sha == null ? "" : sha));
        }
        return new JSONObject()
                .put(Protocol.F_TYPE, Protocol.APPS)
                .put(Protocol.F_CAN_INSTALL, canInstall())
                .put(Protocol.F_ITEMS, items);
    }

    private String install(String pkg, String base64) throws IOException {
        if (!canInstall()) return "GlassLink needs the system install to add apps";
        if (!isAddOnName(pkg) || base64 == null) return "Not a GlassLink app";
        byte[] apk;
        try {
            apk = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return "The app arrived damaged";
        }
        File dir = new File(context.getFilesDir(), "addons");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("mkdir " + dir);
        File file = new File(dir, pkg + ".apk");
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(apk);
        }
        try {
            PackageInfo info = context.getPackageManager().getPackageArchiveInfo(file.getPath(), 0);
            if (info == null) return "The app arrived damaged";
            if (!pkg.equals(info.packageName)) return "Not the app that was asked for";
            if (!signedWithOurKey(file)) return "Not signed with the GlassLink key";
            // The package manager reads the file as another user.
            dir.setExecutable(true, false);
            context.getFilesDir().setExecutable(true, false);
            file.setReadable(true, false);
            return pm("install", "-r", file.getPath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    private String remove(String pkg) throws IOException {
        if (!canInstall()) return "GlassLink needs the system install to remove apps";
        if (!isAddOnName(pkg)) return "Not a GlassLink app";
        try {
            PackageInfo p = context.getPackageManager().getPackageInfo(pkg, PackageManager.GET_SIGNATURES);
            if (!sameKey(p.signatures)) return "Not a GlassLink app";
        } catch (PackageManager.NameNotFoundException e) {
            return null; // already gone
        }
        return pm("uninstall", pkg);
    }

    /**
     * Runs Glass's pm tool. It runs as GlassLink, so it has GlassLink's permissions.
     *
     * @return null on success, otherwise pm's reason
     */
    private static String pm(String... args) throws IOException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "pm";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(readAll(p.getInputStream()), "UTF-8").trim();
        try {
            p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (out.contains("Success")) return null;
        Log.w(TAG, "pm " + Arrays.toString(args) + ": " + out);
        int i = out.indexOf("Failure");
        return i >= 0 ? out.substring(i).split("\n")[0] : "Glass's package manager refused it";
    }

    private boolean isAddOnName(String pkg) {
        return pkg != null && pkg.startsWith(Protocol.ADDON_PREFIX)
                && !pkg.equals(context.getPackageName()) && pkg.matches("[A-Za-z0-9_.]+");
    }

    private Signature[] ownSignatures() {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean sameKey(Signature[] theirs) {
        return theirs != null && theirs.length > 0 && Arrays.equals(theirs, ownSignatures());
    }

    /**
     * Checks the APK's certificate directly. getPackageArchiveInfo doesn't return
     * signatures reliably on Android 4.4.
     */
    private boolean signedWithOurKey(File apk) throws IOException {
        try (JarFile jar = new JarFile(apk)) {
            JarEntry entry = jar.getJarEntry("AndroidManifest.xml");
            if (entry == null) return false;
            try (InputStream in = jar.getInputStream(entry)) {
                readAll(in); // certificates are only known after the entry is read
            }
            Certificate[] certs = entry.getCertificates();
            Signature[] own = ownSignatures();
            if (certs == null || certs.length != own.length) return false;
            for (int i = 0; i < certs.length; i++) {
                if (!Arrays.equals(certs[i].getEncoded(), own[i].toByteArray())) return false;
            }
            return true;
        } catch (SecurityException | CertificateEncodingException e) {
            return false; // tampered or unreadable signature
        }
    }

    private static String sha1(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (IOException | NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
