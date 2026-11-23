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
import android.view.View;
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
    private static final int REQ_LOCATION = 2;

    private Prefs prefs;
    private LinkManager link;

    private TextView statusTitle;
    private TextView statusDetail;
    private View statusDot;
    private View permissionsCard;
    private View notifAccessRow;
    private View btPermRow;
    private TextView glassSummary;
    private View glassEdit;
    private Button glassEditButton;
    private boolean glassEditing;

    private static final String[] TAB_TITLES = {"Glass", "Notifications", "Weather", "Airports"};
    private final View[] pages = new View[4];
    private final View[] tabs = new View[4];
    private final android.widget.ImageView[] tabIcons = new android.widget.ImageView[4];
    private final TextView[] tabLabels = new TextView[4];
    private TextView pageTitle;
    private View headerDot;
    private TextView headerState;
    private int currentTab;
    private TextView notifAccessState;
    private Button notifAccessButton;
    private TextView btPermState;
    private Button btPermButton;
    private EditText address;
    private EditText code;
    private Switch enabled;
    private Switch weatherEnabled;
    private Switch weatherFahrenheit;
    private TextView weatherPlace;
    private TextView weatherLocationState;
    private Button weatherLocationButton;
    private View weatherLocationRow;
    private TextView weatherStatus;
    private WeatherProvider weather;
    private MetarProvider metar;
    private Switch metarEnabled;
    private EditText metarAirports;
    private TextView metarStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = CompanionApp.prefs(this);
        link = CompanionApp.link(this);

        statusTitle = findViewById(R.id.status_title);
        statusDetail = findViewById(R.id.status_detail);
        statusDot = findViewById(R.id.status_dot);
        setUpTabs(savedInstanceState);
        permissionsCard = findViewById(R.id.permissions_card);
        notifAccessRow = findViewById(R.id.notif_access_row);
        btPermRow = findViewById(R.id.bt_perm_row);
        glassSummary = findViewById(R.id.glass_summary);
        glassEdit = findViewById(R.id.glass_edit);
        glassEditButton = findViewById(R.id.glass_edit_button);
        glassEditing = !prefs.isConfigured();
        glassEditButton.setOnClickListener(v -> {
            glassEditing = !glassEditing;
            refresh();
        });
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

        weather = CompanionApp.weather(this);
        weatherEnabled = findViewById(R.id.weather_enabled);
        weatherFahrenheit = findViewById(R.id.weather_fahrenheit);
        weatherPlace = findViewById(R.id.weather_place);
        weatherLocationState = findViewById(R.id.weather_location_state);
        weatherLocationButton = findViewById(R.id.weather_location_button);
        weatherLocationRow = findViewById(R.id.weather_location_row);
        weatherStatus = findViewById(R.id.weather_status);
        weatherEnabled.setChecked(prefs.weatherEnabled());
        weatherFahrenheit.setChecked(prefs.fahrenheit());
        weatherEnabled.setOnCheckedChangeListener((b, on) -> {
            prefs.setWeatherEnabled(on);
            if (on) weather.settingsChanged();
            refresh();
        });
        weatherFahrenheit.setOnCheckedChangeListener((b, on) -> {
            prefs.setFahrenheit(on);
            weather.settingsChanged();
        });
        findViewById(R.id.weather_place_button).setOnClickListener(v -> choosePlace());

        metar = CompanionApp.metar(this);
        metarEnabled = findViewById(R.id.metar_enabled);
        metarAirports = findViewById(R.id.metar_airports);
        metarStatus = findViewById(R.id.metar_status);
        metarEnabled.setChecked(prefs.metarEnabled());
        metarAirports.setText(prefs.metarAirportsText());
        metarEnabled.setOnCheckedChangeListener((b, on) -> {
            prefs.setMetarEnabled(on);
            metar.settingsChanged();
            refresh();
        });
        findViewById(R.id.metar_save).setOnClickListener(v -> saveAirports());
        weatherLocationButton.setOnClickListener(v -> requestLocation());
        findViewById(R.id.weather_refresh).setOnClickListener(v -> {
            weather.settingsChanged();
            Toast.makeText(this, link.isConnected() ? "Updating weather on Glass"
                    : "Will update when Glass connects", Toast.LENGTH_SHORT).show();
            weatherStatus.postDelayed(this::refresh, 4000);
        });

        if (savedInstanceState == null) requestRuntimePermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        link.addListener(this);
        if (prefs.enabled()) GlassLinkService.startIfEnabled(this);
        weather.rememberForegroundLocation();
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
        statusDetail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        LinkManager.State st = link.state();
        int dot = st == LinkManager.State.CONNECTED ? R.color.ok
                : st == LinkManager.State.CONNECTING || st == LinkManager.State.OFF ? R.color.idle
                : R.color.bad;
        statusDot.getBackground().mutate().setTint(getColor(dot));
        headerDot.getBackground().mutate().setTint(getColor(dot));
        headerState.setText(st == LinkManager.State.CONNECTED ? "Connected"
                : st == LinkManager.State.CONNECTING ? "Connecting" : "Offline");

        // The setup card only appears while something is missing.
        boolean access = hasNotificationAccess();
        boolean bt = hasBluetoothPermission();
        notifAccessState.setText("Allow notification access");
        notifAccessRow.setVisibility(access ? View.GONE : View.VISIBLE);
        btPermState.setText("Allow Bluetooth");
        btPermRow.setVisibility(bt ? View.GONE : View.VISIBLE);
        permissionsCard.setVisibility(access && bt ? View.GONE : View.VISIBLE);

        boolean configured = prefs.isConfigured();
        glassSummary.setText(configured
                ? prefs.glassAddress() + "\nPair code saved"
                : "Not set up yet");
        glassEdit.setVisibility(glassEditing || !configured ? View.VISIBLE : View.GONE);
        glassEditButton.setVisibility(configured ? View.VISIBLE : View.GONE);
        glassEditButton.setText(glassEditing ? "Done" : "Edit");

        String place = prefs.placeName();
        weatherPlace.setText(place == null ? "Location: this phone" : "Location: " + place);
        boolean usingPhone = place == null;
        weatherLocationRow.setVisibility(usingPhone ? View.VISIBLE : View.GONE);
        if (!hasLocation()) {
            weatherLocationState.setText("Location access: needed");
            weatherLocationButton.setText("Allow");
            weatherLocationButton.setEnabled(true);
        } else if (!hasBackgroundLocation()) {
            weatherLocationState.setText("Location: only while the app is open");
            weatherLocationButton.setText("All the time");
            weatherLocationButton.setEnabled(true);
        } else {
            weatherLocationRow.setVisibility(View.GONE); // nothing to do
        }
        String ms = prefs.metarStatus();
        metarStatus.setText(ms.isEmpty() ? "METARs come from aviationweather.gov and update every 10 minutes." : ms);
        String status = prefs.weatherStatus();
        weatherStatus.setText(status.isEmpty() ? "Weather is fetched from open-meteo.com using a location rounded to about 1 km." : status);
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
        glassEditing = false;
        refresh();
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

    // ---- Tabs -----------------------------------------------------------------------------

    private void setUpTabs(Bundle savedInstanceState) {
        pageTitle = findViewById(R.id.page_title);
        headerDot = findViewById(R.id.header_dot);
        headerState = findViewById(R.id.header_state);
        int[] pageIds = {R.id.page_glass, R.id.page_notifications, R.id.page_weather, R.id.page_airports};
        int[][] tabIds = {
            {R.id.tab_glass, R.id.tab_glass_icon, R.id.tab_glass_label},
            {R.id.tab_notifications, R.id.tab_notifications_icon, R.id.tab_notifications_label},
            {R.id.tab_weather, R.id.tab_weather_icon, R.id.tab_weather_label},
            {R.id.tab_airports, R.id.tab_airports_icon, R.id.tab_airports_label},
        };
        for (int i = 0; i < 4; i++) {
            final int index = i;
            pages[i] = findViewById(pageIds[i]);
            tabs[i] = findViewById(tabIds[i][0]);
            tabIcons[i] = findViewById(tabIds[i][1]);
            tabLabels[i] = findViewById(tabIds[i][2]);
            tabs[i].setOnClickListener(v -> selectTab(index));
        }
        int start = savedInstanceState != null ? savedInstanceState.getInt("tab", 0)
                : getPreferences(MODE_PRIVATE).getInt("tab", 0);
        // Until pairing is done, the Glass tab is the one that matters.
        if (!prefs.isConfigured()) start = 0;
        selectTab(start);
    }

    private void selectTab(int index) {
        currentTab = Math.max(0, Math.min(3, index));
        for (int i = 0; i < 4; i++) {
            boolean on = i == currentTab;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            int color = getColor(on ? R.color.accent : R.color.muted_text);
            tabIcons[i].setColorFilter(color);
            tabLabels[i].setTextColor(color);
            tabs[i].setSelected(on);
        }
        pageTitle.setText(currentTab == 0 ? getString(R.string.app_name) : TAB_TITLES[currentTab]);
        getPreferences(MODE_PRIVATE).edit().putInt("tab", currentTab).apply();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("tab", currentTab);
    }

    // ---- METAR ----------------------------------------------------------------------------

    private void saveAirports() {
        prefs.setMetarAirportsText(metarAirports.getText().toString().trim());
        List<String> ids = prefs.metarAirports();
        if (ids.isEmpty()) {
            metarAirports.setError("Enter airport codes like KLVN, KMSP");
            return;
        }
        metarAirports.setText(TextUtils.join(", ", ids));
        prefs.setMetarAirportsText(metarAirports.getText().toString());
        if (!prefs.metarEnabled()) {
            metarEnabled.setChecked(true); // also triggers a refresh
        } else {
            metar.settingsChanged();
        }
        Toast.makeText(this, link.isConnected() ? "Sending METARs to Glass"
                : "Will send when Glass connects", Toast.LENGTH_SHORT).show();
        metarStatus.postDelayed(this::refresh, 4000);
    }

    // ---- Weather --------------------------------------------------------------------------

    private void choosePlace() {
        String[] options = {"Use this phone's location", "Search for a place\u2026"};
        new AlertDialog.Builder(this)
                .setTitle("Weather location")
                .setItems(options, (d, which) -> {
                    if (which == 0) {
                        prefs.clearPlace();
                        if (!hasLocation()) requestLocation();
                        weather.settingsChanged();
                        refresh();
                    } else {
                        searchPlace();
                    }
                })
                .show();
    }

    private void searchPlace() {
        final EditText input = new EditText(this);
        input.setHint("City or town");
        input.setSingleLine(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle("Search for a place")
                .setView(box)
                .setPositiveButton("Search", (d, w) -> runSearch(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void runSearch(final String query) {
        if (query.trim().isEmpty()) return;
        new Thread(() -> {
            List<WeatherProvider.Place> found;
            try {
                found = WeatherProvider.search(query);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Search failed. Check your connection.",
                        Toast.LENGTH_LONG).show());
                return;
            }
            final List<WeatherProvider.Place> places = found;
            runOnUiThread(() -> showPlaces(places));
        }, "GlassLink-place-search").start();
    }

    private void showPlaces(final List<WeatherProvider.Place> places) {
        if (isFinishing()) return;
        if (places.isEmpty()) {
            Toast.makeText(this, "No places found", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[places.size()];
        for (int i = 0; i < places.size(); i++) {
            WeatherProvider.Place p = places.get(i);
            labels[i] = p.detail.isEmpty() ? p.name : p.name + "\n" + p.detail;
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose a place")
                .setItems(labels, (d, which) -> {
                    WeatherProvider.Place p = places.get(which);
                    prefs.setPlace(p.name, p.lat, p.lon);
                    weather.settingsChanged();
                    refresh();
                })
                .show();
    }

    private boolean hasLocation() {
        return checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocation() {
        return hasLocation() && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestLocation() {
        if (!hasLocation()) {
            requestPermissions(new String[] {Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        // Android only grants "all the time" from its settings screen, after explaining why.
        new AlertDialog.Builder(this)
                .setTitle("Update weather in the background?")
                .setMessage("Without this, Glass shows the weather for where your phone was the "
                        + "last time you opened GlassLink. On the next screen, choose "
                        + "\"Allow all the time\".")
                .setPositiveButton("Continue", (d, w) -> requestPermissions(
                        new String[] {Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQ_LOCATION))
                .setNegativeButton("Not now", null)
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
        if (requestCode == REQ_LOCATION) {
            if (hasLocation()) {
                weather.rememberForegroundLocation();
                weather.settingsChanged();
            }
            refresh();
            return;
        }
        if (hasBluetoothPermission()) {
            GlassLinkService.startIfEnabled(this);
            link.poke();
        }
        refresh();
    }
}
