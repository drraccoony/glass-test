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

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        link = new LinkManager(this, prefs);
        weather = new WeatherProvider(this, prefs, link);
        link.addHook(weather);
        metar = new MetarProvider(this, prefs, link);
        link.addHook(metar);
        glassSettings = new GlassSettings(prefs, link);
        link.addHook(glassSettings);
        media = new MediaClient(this, link);
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
        link.setChannelSink(hub::deliver);
        link.addListener(() -> hub.setConnected(link.isConnected()));
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
