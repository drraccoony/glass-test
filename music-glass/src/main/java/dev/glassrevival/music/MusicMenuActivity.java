package dev.glassrevival.music;

import android.app.Activity;
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

    private boolean shown;
    private boolean reopen;
    private boolean volumeFirst;

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
        int v = volumeFirst ? -10 : 0; // after a volume step, keep those two on top
        menu.add(Menu.NONE, PLAY_PAUSE, 0, playing ? R.string.pause : R.string.play)
                .setIcon(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        menu.add(Menu.NONE, NEXT, 1, R.string.next).setIcon(R.drawable.ic_next);
        menu.add(Menu.NONE, PREVIOUS, 2, R.string.previous).setIcon(R.drawable.ic_previous);
        menu.add(Menu.NONE, VOL_UP, 3 + v, R.string.volume_up).setIcon(R.drawable.ic_volume_up);
        menu.add(Menu.NONE, VOL_DOWN, 4 + v, R.string.volume_down).setIcon(R.drawable.ic_volume_down);
        menu.add(Menu.NONE, HIDE, 5, R.string.hide).setIcon(R.drawable.ic_close);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        String cmd = null;
        switch (item.getItemId()) {
            case PLAY_PAUSE: cmd = MusicService.isPlaying() ? "pause" : "play"; break;
            case NEXT: cmd = "next"; break;
            case PREVIOUS: cmd = "prev"; break;
            case VOL_UP: cmd = "vol_up"; reopen = volumeFirst = true; break;
            case VOL_DOWN: cmd = "vol_down"; reopen = volumeFirst = true; break;
            case HIDE:
                play(Sounds.DISMISSED);
                MusicService.hide();
                return true;
            default: return false;
        }
        play(MusicService.command(cmd) ? Sounds.SUCCESS : Sounds.DISALLOWED);
        return true;
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        super.onOptionsMenuClosed(menu);
        if (reopen) {
            reopen = false;
            // Reopen after the close animation.
            getWindow().getDecorView().postDelayed(this::openOptionsMenu, 150);
            return;
        }
        finish();
    }

    private void play(int sound) {
        ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(sound);
    }
}
