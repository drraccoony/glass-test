package dev.glassrevival.music;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.media.AudioManager;
import android.view.Menu;
import android.view.MenuItem;
import com.google.android.glass.media.Sounds;

/**
 * Tapping the live card: Play or Pause, Next, Previous, Volume up/down, Hide. After a
 * volume step the menu opens again with the volume items on top, so you can keep tapping.
 */
public class MusicMenuActivity extends Activity {

    private static final int PLAY_PAUSE = 1, NEXT = 2, PREVIOUS = 3, VOL_UP = 4, VOL_DOWN = 5, HIDE = 6;
    /** Opened again after a volume step: volume items first. */
    private static final String EXTRA_VOLUME = "volume";

    private boolean shown;
    private boolean volumeFirst;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        volumeFirst = getIntent().getBooleanExtra(EXTRA_VOLUME, false);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!shown) {
            shown = true;
            openOptionsMenu();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        boolean playing = MusicService.isPlaying();
        // Orders can't be negative: the others start at 10, so 1 and 2 put volume on top.
        boolean v = volumeFirst;
        menu.add(Menu.NONE, PLAY_PAUSE, 10, playing ? R.string.pause : R.string.play)
                .setIcon(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        menu.add(Menu.NONE, NEXT, 11, R.string.next).setIcon(R.drawable.ic_next);
        menu.add(Menu.NONE, PREVIOUS, 12, R.string.previous).setIcon(R.drawable.ic_previous);
        menu.add(Menu.NONE, VOL_UP, v ? 1 : 13, R.string.volume_up).setIcon(R.drawable.ic_volume_up);
        menu.add(Menu.NONE, VOL_DOWN, v ? 2 : 14, R.string.volume_down).setIcon(R.drawable.ic_volume_down);
        menu.add(Menu.NONE, HIDE, 15, R.string.hide).setIcon(R.drawable.ic_close);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        String cmd = null;
        switch (item.getItemId()) {
            case PLAY_PAUSE: cmd = MusicService.isPlaying() ? "pause" : "play"; break;
            case NEXT: cmd = "next"; break;
            case PREVIOUS: cmd = "prev"; break;
            case VOL_UP: cmd = "vol_up"; break;
            case VOL_DOWN: cmd = "vol_down"; break;
            case HIDE:
                play(Sounds.DISMISSED);
                MusicService.hide();
                return true;
            default: return false;
        }
        boolean sent = MusicService.command(cmd);
        play(sent ? Sounds.SUCCESS : Sounds.DISALLOWED);
        if (sent && cmd.startsWith("vol_")) {
            // Glass closes the menu (and this activity) around this call, so a fresh one
            // brings the menu back for the next step.
            startActivity(new Intent(this, MusicMenuActivity.class).putExtra(EXTRA_VOLUME, true));
        }
        return true;
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        super.onOptionsMenuClosed(menu);
        finish();
    }

    private void play(int sound) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(sound);
    }
}
