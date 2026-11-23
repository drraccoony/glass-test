package dev.glassrevival.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import dev.glassrevival.protocol.Protocol;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

public class MainActivity extends Activity implements LinkManager.Listener {

    private static final int REQ_PERMS = 1;

    private Prefs prefs;
    private LinkManager link;

    private TextView statusTitle;
    private TextView statusDetail;
    private TextView notifAccessState;
    private Button notifAccessButton;
    private TextView btPermState;
    private Button btPermButton;
    private EditText address;
    private EditText code;
    private Switch enabled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = CompanionApp.prefs(this);
        link = CompanionApp.link(this);

        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        notifAccessState = findViewById(R.id.notif_access_state);
        notifAccessButton = findViewById(R.id.notif_access_button);
        btPermState = findViewById(R.id.bt_perm_state);
        btPermButton = findViewById(R.id.bt_perm_button);
        address = findViewById(R.id.address);
        code = findViewById(R.id.code);
        enabled = findViewById(R.id.enabled);

        address.setText(prefs.glassAddress());
        code.setText(prefs.pairCode());
        enabled.setChecked(prefs.enabled());

        notifAccessButton.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        btPermButton.setOnClickListener(v -> requestRuntimePermissions());
        findViewById(R.id.choose_paired).setOnClickListener(v -> choosePairedDevice());
        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.test).setOnClickListener(v -> sendTest());
        findViewById(R.id.muted).setOnClickListener(v -> editMutedApps());
        findViewById(R.id.quick_replies).setOnClickListener(v -> editQuickReplies());
        enabled.setOnCheckedChangeListener((button, on) -> setForwarding(on));

        if (savedInstanceState == null) requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addListener(this);
        if (prefs.enabled()) GlassLinkService.startIfEnabled(this);
        refresh();
    }

    @Override
    protected void onPause() {
        link.removeListener(this);
        super.onPause();
    }

    @Override
    public void onLinkChanged() {
        runOnUiThread(this::refresh);
    }

    private void refresh() {
        statusTitle.setText(StatusText.title(link));
        String detail = StatusText.detail(link);
        statusDetail.setText(detail);
        statusTitle.setTextColor(getColor(link.isConnected() ? R.color.ok : R.color.bad));

        boolean access = hasNotificationAccess();
        notifAccessState.setText(access ? "Notification access: allowed" : "Notification access: needed");
        notifAccessButton.setText(access ? "Change" : "Open settings");

        boolean bt = hasBluetoothPermission();
        btPermState.setText(bt ? "Bluetooth: allowed" : "Bluetooth: needed");
        btPermButton.setEnabled(!bt || !hasPostNotifications());
    }

    // ---- Setup ----------------------------------------------------------------------------

    private void save() {
        String addr = address.getText().toString().trim().toUpperCase(Locale.US);
        String pin = code.getText().toString().trim();
        if (!BluetoothAdapter.checkBluetoothAddress(addr)) {
            address.setError("Use the format F8:8F:CA:12:34:56");
            return;
        }
        if (!Protocol.isValidPairCode(pin)) {
            code.setError("Six digits, shown on Glass");
            return;
        }
        prefs.setGlass(addr, pin);
        address.setText(addr);
        if (!prefs.enabled()) {
            enabled.setChecked(true); // triggers setForwarding(true)
        } else {
            GlassLinkService.startIfEnabled(this);
            link.reconnectNow();
        }
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
    }

    private void setForwarding(boolean on) {
        prefs.setEnabled(on);
        if (on) {
            if (!hasBluetoothPermission()) requestRuntimePermissions();
            GlassLinkService.startIfEnabled(this);
        } else {
            GlassLinkService.stop(this);
        }
        refresh();
    }

    @SuppressLint("MissingPermission") // checked by hasBluetoothPermission() first
    private void choosePairedDevice() {
        if (!hasBluetoothPermission()) {
            requestRuntimePermissions();
            return;
        }
        BluetoothManager bm = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = bm == null ? null : bm.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            Toast.makeText(this, "Turn on Bluetooth first", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<BluetoothDevice> devices = new ArrayList<>(adapter.getBondedDevices());
        if (devices.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("No paired devices")
                    .setMessage("Type the address shown on Glass instead. Pairing in Bluetooth "
                            + "settings is optional but gives an encrypted link.")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        String[] labels = new String[devices.size()];
        for (int i = 0; i < devices.size(); i++) {
            BluetoothDevice d = devices.get(i);
            String name = d.getName();
            labels[i] = (TextUtils.isEmpty(name) ? "Unknown" : name) + "\n" + d.getAddress();
        }
        new AlertDialog.Builder(this)
                .setTitle("Paired devices")
                .setItems(labels, (dialog, which) -> address.setText(devices.get(which).getAddress()))
                .show();
    }

    private void sendTest() {
        if (!link.isConnected()) {
            Toast.makeText(this, "Not connected to Glass yet", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            JSONObject msg = new JSONObject();
            msg.put(Protocol.F_TYPE, Protocol.NOTIFY);
            msg.put(Protocol.F_KEY, "glasslink:test:" + System.currentTimeMillis());
            msg.put(Protocol.F_PACKAGE, getPackageName());
            msg.put(Protocol.F_APP, getString(R.string.app_name));
            msg.put(Protocol.F_TITLE, "Hello from your phone");
            msg.put(Protocol.F_TEXT, "If you can read this on Glass, notifications are working. "
                    + "Tap the touchpad to dismiss it.");
            msg.put(Protocol.F_WHEN, System.currentTimeMillis());
            msg.put(Protocol.F_ALERT, true);
            String icon = NotificationMapper.encodeIcon(getDrawable(R.drawable.ic_launcher));
            if (icon != null) msg.put(Protocol.F_ICON, icon);
            link.send(msg);
            Toast.makeText(this, "Sent", Toast.LENGTH_SHORT).show();
        } catch (JSONException e) {
            Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show();
        }
    }

    private void editMutedApps() {
        PackageManager pm = getPackageManager();
        Set<String> seen = prefs.seen();
        final Set<String> muted = prefs.muted();
        seen.addAll(muted);
        if (seen.isEmpty()) {
            Toast.makeText(this, "Apps appear here after they post a notification",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final List<String[]> apps = new ArrayList<>(); // {label, package}
        for (String pkg : seen) {
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = pm.getApplicationLabel(ai).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // keep package name
            }
            apps.add(new String[] {label, pkg});
        }
        Collections.sort(apps, (a, b) -> a[0].compareToIgnoreCase(b[0]));
        String[] labels = new String[apps.size()];
        boolean[] checked = new boolean[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            labels[i] = apps.get(i)[0];
            checked[i] = muted.contains(apps.get(i)[1]);
        }
        final Set<String> result = new HashSet<>(muted);
        new AlertDialog.Builder(this)
                .setTitle("Don't send these apps to Glass")
                .setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
                    if (isChecked) result.add(apps.get(which)[1]);
                    else result.remove(apps.get(which)[1]);
                })
                .setPositiveButton(android.R.string.ok, (dialog, which) -> prefs.setMuted(result))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void editQuickReplies() {
        final EditText input = new EditText(this);
        input.setText(prefs.quickRepliesText());
        input.setMinLines(4);
        input.setGravity(android.view.Gravity.TOP);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Quick replies")
                .setMessage("One per line. Glass shows these when you reply to a message, "
                        + "after any suggestions from the app.")
                .setView(box)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> prefs.setQuickRepliesText(input.getText().toString()))
                .setNeutralButton("Reset", (dialog, which) ->
                        prefs.setQuickRepliesText(Prefs.DEFAULT_QUICK_REPLIES))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---- Permissions ----------------------------------------------------------------------

    private boolean hasNotificationAccess() {
        String enabledListeners = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (enabledListeners == null) return false;
        String me = new ComponentName(this, GlassNotificationListener.class).flattenToString();
        for (String entry : enabledListeners.split(":")) {
            if (me.equals(entry)) return true;
        }
        return false;
    }

    private boolean hasBluetoothPermission() {
        return Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasPostNotifications() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRuntimePermissions() {
        List<String> wanted = new ArrayList<>();
        if (!hasBluetoothPermission()) wanted.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (!hasPostNotifications()) wanted.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!wanted.isEmpty()) requestPermissions(wanted.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (hasBluetoothPermission()) {
            GlassLinkService.startIfEnabled(this);
            link.poke();
        }
        refresh();
    }
}
