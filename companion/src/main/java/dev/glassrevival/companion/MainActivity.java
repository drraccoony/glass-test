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
import android.widget.LinearLayout;
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

    private static final String[] TAB_TITLES = {"Glass", "Notifications", "Photos", "Weather", "Airports"};
    private static final int TAB_PHOTOS = 2;
    private final View[] pages = new View[5];
    private final View[] tabs = new View[5];
    private final android.widget.ImageView[] tabIcons = new android.widget.ImageView[5];
    private final TextView[] tabLabels = new TextView[5];
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
        setUpPhotos();
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
        setUpNotificationOptions();
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
        runOnUiThread(() -> {
            refresh();
            // Load the gallery once the link comes up while the Photos tab is open.
            boolean connected = link.isConnected();
            if (connected && !photosRequested && currentTab == TAB_PHOTOS && !media.listLoaded()) {
                loadPhotos();
            }
            if (!connected) photosRequested = false;
            updatePhotosStatus();
        });
    }

    @Override
    protected void onDestroy() {
        if (media != null) media.removeListener(mediaListener);
        super.onDestroy();
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

    // ---- Notification options ---------------------------------------------------------------

    private static final int[] ALERT_SECONDS = {5, 10, 20, 30};
    private Button alertSecondsButton;

    private void setUpNotificationOptions() {
        GlassSettings glass = CompanionApp.glassSettings(this);
        bindSwitch(R.id.opt_wake, prefs.wakeGlass(), on -> {
            prefs.setWakeGlass(on);
            glass.push();
        });
        bindSwitch(R.id.opt_sound, prefs.playSound(), on -> {
            prefs.setPlaySound(on);
            glass.push();
        });
        bindSwitch(R.id.opt_only_locked, prefs.onlyWhenLocked(), prefs::setOnlyWhenLocked);
        bindSwitch(R.id.opt_follow_dnd, prefs.followDnd(), prefs::setFollowDnd);
        bindSwitch(R.id.opt_show_text, prefs.showText(), on -> {
            prefs.setShowText(on);
            link.reconnectNow(); // resend what's on Glass with the new setting
        });
        bindSwitch(R.id.opt_include_silent, prefs.includeSilent(), on -> {
            prefs.setIncludeSilent(on);
            link.reconnectNow();
        });
        alertSecondsButton = findViewById(R.id.opt_alert_seconds);
        alertSecondsButton.setText(prefs.alertSeconds() + " seconds");
        alertSecondsButton.setOnClickListener(v -> {
            String[] labels = new String[ALERT_SECONDS.length];
            int checked = 1;
            for (int i = 0; i < ALERT_SECONDS.length; i++) {
                labels[i] = ALERT_SECONDS[i] + " seconds";
                if (ALERT_SECONDS[i] == prefs.alertSeconds()) checked = i;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Keep alerts on screen")
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        prefs.setAlertSeconds(ALERT_SECONDS[which]);
                        alertSecondsButton.setText(labels[which]);
                        glass.push();
                        d.dismiss();
                    })
                    .show();
        });
    }

    private interface OnToggle {
        void set(boolean on);
    }

    private void bindSwitch(int id, boolean value, OnToggle onToggle) {
        Switch sw = findViewById(id);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((b, on) -> onToggle.set(on));
    }

    // ---- Tabs -----------------------------------------------------------------------------

    private void setUpTabs(Bundle savedInstanceState) {
        pageTitle = findViewById(R.id.page_title);
        headerDot = findViewById(R.id.header_dot);
        headerState = findViewById(R.id.header_state);
        int[] pageIds = {R.id.page_glass, R.id.page_notifications, R.id.page_photos, R.id.page_weather,
            R.id.page_airports};
        int[][] tabIds = {
            {R.id.tab_glass, R.id.tab_glass_icon, R.id.tab_glass_label},
            {R.id.tab_notifications, R.id.tab_notifications_icon, R.id.tab_notifications_label},
            {R.id.tab_photos, R.id.tab_photos_icon, R.id.tab_photos_label},
            {R.id.tab_weather, R.id.tab_weather_icon, R.id.tab_weather_label},
            {R.id.tab_airports, R.id.tab_airports_icon, R.id.tab_airports_label},
        };
        for (int i = 0; i < 5; i++) {
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
        currentTab = Math.max(0, Math.min(4, index));
        for (int i = 0; i < 5; i++) {
            boolean on = i == currentTab;
            pages[i].setVisibility(on ? View.VISIBLE : View.GONE);
            int color = getColor(on ? R.color.accent : R.color.muted_text);
            tabIcons[i].setColorFilter(color);
            tabLabels[i].setTextColor(color);
            tabs[i].setSelected(on);
        }
        pageTitle.setText(currentTab == 0 ? getString(R.string.app_name) : TAB_TITLES[currentTab]);
        getPreferences(MODE_PRIVATE).edit().putInt("tab", currentTab).apply();
        if (currentTab == TAB_PHOTOS) onPhotosShown();
    }

    // ---- Photos -----------------------------------------------------------------------------

    private static final int FILTER_ALL = 0;
    private static final int FILTER_PHOTOS = 1;
    private static final int FILTER_VIDEOS = 2;

    private MediaClient media;
    private GalleryAdapter gallery;
    private TextView photosStatus;
    private final Button[] filterButtons = new Button[3];
    private int filter;
    private boolean hideOverlayCopies;
    private int columns = 3;
    private List<MediaClient.Item> shown = new ArrayList<>();
    private final java.util.LinkedHashSet<String> selected = new java.util.LinkedHashSet<>();
    private android.view.ActionMode selectionMode;

    private final MediaClient.Listener mediaListener = new MediaClient.Listener() {
        @Override
        public void onItems(List<MediaClient.Item> items) {
            selected.retainAll(idsOf(items));
            rebuildGallery();
            updatePhotosStatus();
        }

        @Override
        public void onThumb(String id) {
            gallery.notifyDataSetChanged();
        }

        @Override
        public void onDeleted(int deleted, int failed) {
            Toast.makeText(MainActivity.this, failed == 0 ? "Deleted " + deleted + " from Glass"
                    : "Deleted " + deleted + ", " + failed + " couldn't be deleted", Toast.LENGTH_SHORT).show();
        }
    };

    private static java.util.Set<String> idsOf(List<MediaClient.Item> items) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (MediaClient.Item it : items) ids.add(it.id);
        return ids;
    }

    private void setUpPhotos() {
        media = CompanionApp.media(this);
        android.content.SharedPreferences ui = getPreferences(MODE_PRIVATE);
        filter = ui.getInt("photo_filter", FILTER_ALL);
        hideOverlayCopies = ui.getBoolean("photo_hide_overlay", false);
        columns = ui.getInt("photo_columns", 3);
        photosStatus = findViewById(R.id.photos_status);
        android.widget.ListView list = findViewById(R.id.photos_list);
        gallery = new GalleryAdapter();
        list.setAdapter(gallery);
        int[] ids = {R.id.filter_all, R.id.filter_photos, R.id.filter_videos};
        for (int i = 0; i < 3; i++) {
            final int f = i;
            filterButtons[i] = findViewById(ids[i]);
            filterButtons[i].setOnClickListener(v -> {
                filter = f;
                getPreferences(MODE_PRIVATE).edit().putInt("photo_filter", f).apply();
                rebuildGallery();
            });
        }
        Switch hide = findViewById(R.id.filter_hide_overlay);
        hide.setChecked(hideOverlayCopies);
        hide.setOnCheckedChangeListener((b, on) -> {
            hideOverlayCopies = on;
            getPreferences(MODE_PRIVATE).edit().putBoolean("photo_hide_overlay", on).apply();
            rebuildGallery();
        });
        findViewById(R.id.photos_refresh).setOnClickListener(v -> loadPhotos());
        // Pinch the grid to change how many thumbnails fit across.
        final android.view.ScaleGestureDetector pinch = new android.view.ScaleGestureDetector(this,
                new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    private float accumulated = 1f;

                    @Override
                    public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                        accumulated = 1f;
                        return true;
                    }

                    @Override
                    public boolean onScale(android.view.ScaleGestureDetector d) {
                        accumulated *= d.getScaleFactor();
                        int next = columns;
                        if (accumulated > 1.25f) next = columns - 1;
                        if (accumulated < 0.8f) next = columns + 1;
                        next = Math.max(2, Math.min(5, next));
                        if (next != columns) {
                            columns = next;
                            accumulated = 1f;
                            getPreferences(MODE_PRIVATE).edit().putInt("photo_columns", columns).apply();
                            rebuildGallery();
                        }
                        return true;
                    }
                });
        list.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            return pinch.isInProgress();
        });
        media.addListener(mediaListener);
        rebuildGallery();
        updatePhotosStatus();
    }

    private void onPhotosShown() {
        if (media != null && !media.listLoaded()) loadPhotos();
    }

    private boolean photosRequested;

    private void loadPhotos() {
        photosRequested = true;
        if (!link.isConnected()) {
            photosStatus.setText("Connect to Glass to see its photos and videos");
            return;
        }
        photosStatus.setText("Loading from Glass…");
        media.refreshList();
    }

    private void updatePhotosStatus() {
        if (media == null) return;
        if (!media.listLoaded()) {
            photosStatus.setText(link.isConnected() ? "" : "Connect to Glass to see its photos and videos");
            return;
        }
        int photos = 0;
        int videos = 0;
        for (MediaClient.Item it : shown) {
            if (it.isVideo()) videos++; else photos++;
        }
        photosStatus.setText(photos + (photos == 1 ? " photo, " : " photos, ")
                + videos + (videos == 1 ? " video" : " videos"));
    }

    /** Applies the filter and regroups by day. */
    private void rebuildGallery() {
        List<MediaClient.Item> list = new ArrayList<>();
        for (MediaClient.Item it : media.items()) {
            if (filter == FILTER_PHOTOS && it.isVideo()) continue;
            if (filter == FILTER_VIDEOS && !it.isVideo()) continue;
            if (hideOverlayCopies && it.name.endsWith("_x.jpg")) continue;
            list.add(it);
        }
        shown = list;
        for (int i = 0; i < 3; i++) {
            filterButtons[i].setTextColor(getColor(i == filter ? R.color.accent : R.color.muted_text));
            filterButtons[i].setTypeface(null, i == filter ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
        }
        gallery.rebuild();
        updatePhotosStatus();
    }

    private String dayLabel(long time) {
        java.util.Calendar day = java.util.Calendar.getInstance();
        day.setTimeInMillis(time);
        java.util.Calendar today = java.util.Calendar.getInstance();
        if (sameDay(day, today)) return "Today";
        today.add(java.util.Calendar.DAY_OF_YEAR, -1);
        if (sameDay(day, today)) return "Yesterday";
        boolean thisYear = day.get(java.util.Calendar.YEAR) == java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        String pattern = android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(),
                thisYear ? "EEEMMMd" : "EEEMMMdyyyy");
        return android.text.format.DateFormat.format(pattern, time).toString();
    }

    private static boolean sameDay(java.util.Calendar a, java.util.Calendar b) {
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /** Rows are either a day heading or a strip of up to {@code columns} thumbnails. */
    private final class GalleryAdapter extends android.widget.BaseAdapter {
        private static final int HEADER = 0;
        private static final int ROW = 1;

        private final List<Object[]> rows = new ArrayList<>(); // {HEADER, label, List<Item>} or {ROW, List<Item>}

        void rebuild() {
            rows.clear();
            String currentLabel = null;
            List<MediaClient.Item> dayItems = null;
            List<MediaClient.Item> row = null;
            for (MediaClient.Item it : shown) {
                String label = dayLabel(it.taken);
                if (!label.equals(currentLabel)) {
                    currentLabel = label;
                    dayItems = new ArrayList<>();
                    rows.add(new Object[] {HEADER, label, dayItems});
                    row = null;
                }
                dayItems.add(it);
                if (row == null || row.size() == columns) {
                    row = new ArrayList<>();
                    rows.add(new Object[] {ROW, row});
                }
                row.add(it);
            }
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return (Integer) rows.get(position)[0];
        }

        @Override
        public boolean isEnabled(int position) {
            return false; // cells handle their own taps
        }

        @Override
        @SuppressWarnings("unchecked")
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            Object[] r = rows.get(position);
            if ((Integer) r[0] == HEADER) {
                return headerView(convertView, parent, (String) r[1], (List<MediaClient.Item>) r[2]);
            }
            return rowView(convertView, (List<MediaClient.Item>) r[1]);
        }

        private View headerView(View convertView, android.view.ViewGroup parent, String label,
                                final List<MediaClient.Item> dayItems) {
            LinearLayout h = (LinearLayout) convertView;
            if (h == null) {
                h = new LinearLayout(MainActivity.this);
                h.setOrientation(LinearLayout.HORIZONTAL);
                h.setGravity(android.view.Gravity.CENTER_VERTICAL);
                h.setPadding(dp(4), dp(14), 0, dp(4));
                TextView t = new TextView(MainActivity.this);
                t.setTextSize(15);
                t.setTypeface(null, android.graphics.Typeface.BOLD);
                h.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                Button select = new Button(MainActivity.this, null, 0, R.style.TextButton);
                select.setMinWidth(0);
                select.setMinHeight(0);
                select.setPadding(dp(12), dp(4), dp(12), dp(4));
                h.addView(select);
            }
            ((TextView) h.getChildAt(0)).setText(label + "  ·  " + dayItems.size());
            Button select = (Button) h.getChildAt(1);
            boolean all = !dayItems.isEmpty() && selected.containsAll(idsOf(dayItems));
            select.setText(all ? "Clear" : "Select");
            select.setOnClickListener(v -> {
                for (MediaClient.Item it : dayItems) {
                    if (all) selected.remove(it.id); else selected.add(it.id);
                }
                selectionChanged();
            });
            return h;
        }

        private View rowView(View convertView, List<MediaClient.Item> items) {
            LinearLayout row = (LinearLayout) convertView;
            if (row == null || row.getChildCount() != columns) {
                row = new LinearLayout(MainActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                for (int i = 0; i < columns; i++) {
                    View cell = getLayoutInflater().inflate(R.layout.media_cell, row, false);
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) cell.getLayoutParams();
                    lp.setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
                    row.addView(cell, lp);
                }
            }
            for (int i = 0; i < columns; i++) {
                View cell = row.getChildAt(i);
                if (i >= items.size()) {
                    cell.setVisibility(View.INVISIBLE);
                    continue;
                }
                cell.setVisibility(View.VISIBLE);
                bindCell(cell, items.get(i));
            }
            return row;
        }
    }

    private void bindCell(View cell, final MediaClient.Item it) {
        android.widget.ImageView image = cell.findViewById(R.id.cell_image);
        TextView badge = cell.findViewById(R.id.cell_badge);
        image.setImageBitmap(media.thumb(it.id));
        if (it.isVideo()) {
            long s = it.duration / 1000;
            badge.setText("▶ " + (s / 60) + ":" + String.format(Locale.US, "%02d", s % 60));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
        boolean isSelected = selected.contains(it.id);
        cell.findViewById(R.id.cell_dim).setVisibility(isSelected ? View.VISIBLE : View.GONE);
        cell.findViewById(R.id.cell_check).setVisibility(isSelected ? View.VISIBLE : View.GONE);
        cell.setOnClickListener(v -> {
            if (selectionMode != null) {
                toggle(it);
            } else {
                openViewer(it);
            }
        });
        cell.setOnLongClickListener(v -> {
            toggle(it);
            return true;
        });
    }

    private void openViewer(MediaClient.Item it) {
        String[] ids = new String[shown.size()];
        int index = 0;
        for (int i = 0; i < shown.size(); i++) {
            ids[i] = shown.get(i).id;
            if (shown.get(i).id.equals(it.id)) index = i;
        }
        startActivity(new Intent(this, MediaViewerActivity.class)
                .putExtra(MediaViewerActivity.EXTRA_IDS, ids)
                .putExtra(MediaViewerActivity.EXTRA_INDEX, index));
    }

    private void toggle(MediaClient.Item it) {
        if (!selected.remove(it.id)) selected.add(it.id);
        selectionChanged();
    }

    private void selectionChanged() {
        if (selected.isEmpty()) {
            if (selectionMode != null) selectionMode.finish();
        } else if (selectionMode == null) {
            selectionMode = startActionMode(selectionCallback);
        }
        if (selectionMode != null) selectionMode.setTitle(selected.size() + " selected");
        gallery.notifyDataSetChanged();
    }

    private static final int ACTION_SAVE = 1;
    private static final int ACTION_SHARE = 2;
    private static final int ACTION_DELETE = 3;
    private static final int ACTION_ALL = 4;

    private final android.view.ActionMode.Callback selectionCallback = new android.view.ActionMode.Callback() {
        @Override
        public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
            int flags = android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM | android.view.MenuItem.SHOW_AS_ACTION_WITH_TEXT;
            menu.add(0, ACTION_SAVE, 0, "Save").setShowAsAction(flags);
            menu.add(0, ACTION_SHARE, 1, "Share").setShowAsAction(flags);
            menu.add(0, ACTION_DELETE, 2, "Delete").setShowAsAction(flags);
            menu.add(0, ACTION_ALL, 3, "Select all").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
            List<MediaClient.Item> chosen = new ArrayList<>();
            for (MediaClient.Item it : shown) if (selected.contains(it.id)) chosen.add(it);
            switch (item.getItemId()) {
                case ACTION_SAVE:
                    runBulk(chosen, false);
                    return true;
                case ACTION_SHARE:
                    runBulk(chosen, true);
                    return true;
                case ACTION_DELETE:
                    confirmBulkDelete(chosen);
                    return true;
                case ACTION_ALL:
                    for (MediaClient.Item it : shown) selected.add(it.id);
                    selectionChanged();
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public void onDestroyActionMode(android.view.ActionMode mode) {
            selectionMode = null;
            selected.clear();
            gallery.notifyDataSetChanged();
        }
    };

    private void confirmBulkDelete(final List<MediaClient.Item> chosen) {
        new AlertDialog.Builder(this)
                .setTitle("Delete " + chosen.size() + (chosen.size() == 1 ? " item" : " items") + " from Glass?")
                .setMessage("They're removed from Glass permanently. Copies you saved to the phone are kept.")
                .setPositiveButton("Delete", (d, w) -> {
                    List<String> ids = new ArrayList<>();
                    for (MediaClient.Item it : chosen) ids.add(it.id);
                    media.deleteOnGlass(ids);
                    if (selectionMode != null) selectionMode.finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Downloads each item in turn, then saves them all to the gallery or opens the share sheet. */
    private void runBulk(final List<MediaClient.Item> chosen, final boolean share) {
        if (chosen.isEmpty()) return;
        final List<java.io.File> files = new ArrayList<>();
        final List<MediaClient.Item> done = new ArrayList<>();
        final int[] failed = {0};
        final boolean[] cancelled = {false};
        final android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, 0,
                android.R.style.Widget_Material_ProgressBar_Horizontal);
        bar.setMax(100);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(share ? "Preparing to share" : "Saving to phone")
                .setMessage("Starting…")
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    cancelled[0] = true;
                    media.cancelDownload();
                })
                .show();
        new Object() {
            int i = 0;

            void next() {
                if (cancelled[0]) return;
                if (i >= chosen.size()) {
                    finish();
                    return;
                }
                final MediaClient.Item it = chosen.get(i);
                media.download(it, new MediaClient.DownloadListener() {
                    @Override
                    public void onProgress(long d, long total, boolean viaWifi) {
                        int pct = total > 0 ? (int) (d * 100 / total) : 0;
                        bar.setProgress((i * 100 + pct) / chosen.size());
                        dialog.setMessage((i + 1) + " of " + chosen.size()
                                + (viaWifi ? " over Wi-Fi" : " over Bluetooth"));
                    }

                    @Override
                    public void onDone(java.io.File f) {
                        if (share) {
                            files.add(f);
                            done.add(it);
                        } else if (!MediaSaver.save(MainActivity.this, it, f)) {
                            failed[0]++;
                        }
                        i++;
                        next();
                    }

                    @Override
                    public void onError(String message) {
                        failed[0]++;
                        i++;
                        next();
                    }
                });
            }

            void finish() {
                dialog.dismiss();
                if (selectionMode != null) selectionMode.finish();
                if (share) {
                    if (!files.isEmpty()) startActivity(MediaSaver.shareIntent(done, files));
                } else {
                    int saved = chosen.size() - failed[0];
                    Toast.makeText(MainActivity.this, "Saved " + saved + " to your gallery (GlassLink folder)"
                            + (failed[0] > 0 ? ", " + failed[0] + " failed" : ""), Toast.LENGTH_LONG).show();
                }
            }
        }.next();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
