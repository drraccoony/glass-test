package dev.glassrevival.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** "ok glass, listen to": shows the live card (even if nothing plays) and gets out of the way. */
public class MusicLaunchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, MusicService.class).putExtra(MusicService.EXTRA_SHOW, true));
        finish();
    }
}
