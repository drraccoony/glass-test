package dev.glassrevival.companion;

import android.app.Application;
import android.content.Context;
import dev.glassrevival.hub.HubHost;
import org.json.JSONObject;

public class CompanionApp extends Application {
    private LinkManager link;
    private Prefs prefs;
    private WeatherProvider weather;
    private MetarProvider metar;
    private GlassSettings glassSettings;
    private MediaClient media;
    private HubHost hub;
    private LocationSharer location;
    private NavProvider nav;
    private AddOnManager addOns;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        link = new LinkManager(this, prefs);
        addOns = new AddOnManager(this, link);
        link.addSink(addOns);
        weather = new WeatherProvider(this, prefs, link);
        link.addHook(weather);
        metar = new MetarProvider(this, prefs, link);
        link.addHook(metar);
        glassSettings = new GlassSettings(prefs, link);
        link.addHook(glassSettings);
        media = new MediaClient(this, link);
        location = new LocationSharer(this, prefs, link);
        link.addHook(location);
        hub = new HubHost(this, new HubHost.Link() {
            @Override
            public boolean isConnected() {
                return link.isConnected();
            }

            @Override
            public boolean send(JSONObject frame) {
                if (!link.isConnected()) return false;
                link.send(frame);
                return true;
            }
        });
        nav = new NavProvider(this, link);
        link.addHook(nav);
        // Channels the companion serves itself; everything else goes to add-on apps.
        link.setChannelSink(msg -> {
            String channel = msg.optString(dev.glassrevival.protocol.Protocol.F_CHANNEL);
            if (NavProvider.CHANNEL.equals(channel)) {
                nav.onGlassMessage(msg);
            } else if (MetarProvider.CHANNEL.equals(channel)) {
                metar.onGlassMessage(msg);
            } else if (WeatherProvider.CHANNEL.equals(channel)) {
                weather.onGlassMessage(msg);
            } else {
                hub.deliver(msg);
            }
        });
        link.addListener(() -> hub.setConnected(link.isConnected()));
        // A freshly installed Airports or Weather app gets its data right away.
        addOns.addInstallListener(pkg -> {
            if (AddOnManager.AIRPORTS.pkg.equals(pkg)) metar.glassAppInstalled();
            if (AddOnManager.WEATHER.pkg.equals(pkg)) weather.glassAppInstalled();
        });
    }

    static AddOnManager addOns(Context context) {
        return ((CompanionApp) context.getApplicationContext()).addOns;
    }

    static NavProvider nav(Context context) {
        return ((CompanionApp) context.getApplicationContext()).nav;
    }

    static LocationSharer location(Context context) {
        return ((CompanionApp) context.getApplicationContext()).location;
    }

    static HubHost hub(Context context) {
        return ((CompanionApp) context.getApplicationContext()).hub;
    }

    static MediaClient media(Context context) {
        return ((CompanionApp) context.getApplicationContext()).media;
    }

    static GlassSettings glassSettings(Context context) {
        return ((CompanionApp) context.getApplicationContext()).glassSettings;
    }

    static MetarProvider metar(Context context) {
        return ((CompanionApp) context.getApplicationContext()).metar;
    }

    static WeatherProvider weather(Context context) {
        return ((CompanionApp) context.getApplicationContext()).weather;
    }

    static LinkManager link(Context context) {
        return ((CompanionApp) context.getApplicationContext()).link;
    }

    static Prefs prefs(Context context) {
        return ((CompanionApp) context.getApplicationContext()).prefs;
    }
}
