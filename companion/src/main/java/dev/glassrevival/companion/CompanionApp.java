package dev.glassrevival.companion;

import android.app.Application;
import android.content.Context;

public class CompanionApp extends Application {
    private LinkManager link;
    private Prefs prefs;
    private WeatherProvider weather;
    private MetarProvider metar;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        link = new LinkManager(this, prefs);
        weather = new WeatherProvider(this, prefs, link);
        link.addHook(weather);
        metar = new MetarProvider(this, prefs, link);
        link.addHook(metar);
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
