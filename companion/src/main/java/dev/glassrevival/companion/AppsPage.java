package dev.glassrevival.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The Apps tab: one card per Glass add-on. Tap a card for what it does, Install / Update /
 * Remove, and its settings (Airports has some). Installing needs GlassLink's system install
 * on Glass; without it, each card shows the glasslink.sh command instead.
 */
final class AppsPage implements AddOnManager.Listener {

    private final Activity activity;
    private final AddOnManager addOns;
    private final LinkManager link;
    private final SharedPreferences ui;
    private final TextView note;
    private final List<Card> cards = new ArrayList<>();

    private static final class Card {
        final AddOnManager.AddOn addOn;
        final View root, details, chevron, settings;
        final TextView status, error;
        final Button install, remove;

        Card(AddOnManager.AddOn addOn, View root) {
            this.addOn = addOn;
            this.root = root;
            details = root.findViewById(R.id.addon_details);
            chevron = root.findViewById(R.id.addon_chevron);
            settings = root.findViewById(R.id.addon_settings);
            status = root.findViewById(R.id.addon_status);
            error = root.findViewById(R.id.addon_error);
            install = root.findViewById(R.id.addon_install);
            remove = root.findViewById(R.id.addon_remove);
        }
    }

    AppsPage(Activity activity, AddOnManager addOns, LinkManager link) {
        this.activity = activity;
        this.addOns = addOns;
        this.link = link;
        ui = activity.getPreferences(Activity.MODE_PRIVATE);
        note = activity.findViewById(R.id.apps_note);
        LinearLayout list = activity.findViewById(R.id.apps_list);
        View airportsSettings = activity.findViewById(R.id.airports_settings);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (AddOnManager.AddOn a : AddOnManager.ALL) {
            View root = inflater.inflate(R.layout.addon_card, list, false);
            list.addView(root);
            Card c = new Card(a, root);
            cards.add(c);
            ((TextView) root.findViewById(R.id.addon_name)).setText(a.name);
            ((TextView) root.findViewById(R.id.addon_summary)).setText(a.summary);
            if (a == AddOnManager.AIRPORTS) {
                ((ViewGroup) airportsSettings.getParent()).removeView(airportsSettings);
                ((ViewGroup) c.settings).addView(airportsSettings);
            }
            root.findViewById(R.id.addon_header).setOnClickListener(v -> {
                ui.edit().putBoolean(openKey(a), !isOpen(a)).apply();
                bind(c);
            });
            c.install.setOnClickListener(v -> addOns.install(a));
            c.remove.setOnClickListener(v -> new AlertDialog.Builder(activity)
                    .setTitle("Remove " + a.name + " from Glass?")
                    .setMessage("You can install it again from here.")
                    .setPositiveButton("Remove", (d, w) -> addOns.remove(a))
                    .setNegativeButton("Cancel", null)
                    .show());
        }
    }

    void onResume() {
        addOns.addListener(this);
        refresh();
    }

    void onPause() {
        addOns.removeListener(this);
    }

    @Override
    public void onAddOnsChanged() {
        refresh();
    }

    void refresh() {
        AddOnManager.Status any = addOns.status(AddOnManager.AIRPORTS);
        if (any == AddOnManager.Status.UNKNOWN) {
            note.setText("Connect Glass to see which of these apps it has.");
        } else if (!addOns.canInstall()) {
            note.setText("To install and remove apps from here, GlassLink on Glass needs its system"
                    + " install. From your PC, run ./scripts/glasslink.sh install-glass-system once.");
        } else {
            note.setText("These apps run on Glass and come with GlassLink. Tap one for details.");
        }
        for (Card c : cards) bind(c);
    }

    private void bind(Card c) {
        AddOnManager.AddOn a = c.addOn;
        AddOnManager.Status st = addOns.status(a);
        String busy = addOns.busy(a);
        String version = addOns.version(a);
        String status;
        if ("install".equals(busy)) {
            status = st == AddOnManager.Status.NOT_INSTALLED ? "Installing…" : "Updating…";
        } else if ("remove".equals(busy)) {
            status = "Removing…";
        } else {
            switch (st) {
                case NOT_INSTALLED: status = "Not installed"; break;
                case UPDATE: status = "Update available"; break;
                case INSTALLED: status = "Installed" + (version == null || version.isEmpty() ? "" : " · " + version); break;
                default: status = "Not checked yet"; break;
            }
        }
        c.status.setText(status);
        c.status.setTextColor(activity.getColor(st == AddOnManager.Status.UPDATE ? R.color.accent : R.color.muted_text));

        boolean open = isOpen(a);
        c.details.setVisibility(open ? View.VISIBLE : View.GONE);
        c.chevron.setRotation(open ? 180 : 0);

        String error = addOns.error(a);
        boolean manual = st != AddOnManager.Status.UNKNOWN && !addOns.canInstall();
        if (error == null && manual && st != AddOnManager.Status.INSTALLED) {
            error = "Install from your PC: ./scripts/glasslink.sh " + a.script;
        }
        c.error.setText(error == null ? "" : error);
        c.error.setVisibility(error == null ? View.GONE : View.VISIBLE);
        c.error.setTextColor(activity.getColor(addOns.error(a) != null ? R.color.bad : R.color.muted_text));

        boolean actionable = addOns.canInstall() && busy == null && link.isConnected();
        boolean installable = st == AddOnManager.Status.NOT_INSTALLED || st == AddOnManager.Status.UPDATE;
        boolean removable = st == AddOnManager.Status.INSTALLED || st == AddOnManager.Status.UPDATE;
        c.install.setText(st == AddOnManager.Status.UPDATE ? "Update" : "Install");
        c.install.setVisibility(addOns.canInstall() && installable ? View.VISIBLE : View.GONE);
        c.remove.setVisibility(addOns.canInstall() && removable ? View.VISIBLE : View.GONE);
        c.install.setEnabled(actionable);
        c.remove.setEnabled(actionable);
        // Settings only matter while the app is on Glass (or before Glass has reported).
        c.settings.setVisibility(st == AddOnManager.Status.NOT_INSTALLED ? View.GONE : View.VISIBLE);
    }

    private boolean isOpen(AddOnManager.AddOn a) {
        return ui.getBoolean(openKey(a), a == AddOnManager.AIRPORTS);
    }

    private static String openKey(AddOnManager.AddOn a) {
        return "apps_open_" + a.pkg;
    }
}
