package dev.glassrevival.nav;

import android.app.Activity;
import android.media.AudioManager;
import android.view.Menu;
import android.view.MenuItem;
import com.google.android.glass.media.Sounds;

/** Tapping the live card: a menu with "Stop navigation", the usual Glass pattern. */
public class NavMenuActivity extends Activity {

    private static final int MENU_STOP = 1;
    private boolean shown;

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
        menu.add(Menu.NONE, MENU_STOP, 0, R.string.stop_navigation).setIcon(R.drawable.ic_nav);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_STOP) {
            ((AudioManager) getSystemService(AUDIO_SERVICE)).playSoundEffect(Sounds.DISMISSED);
            NavService.requestStop(); // the phone ends Maps navigation, then sends "end"
        }
        return true;
    }

    @Override
    public void onOptionsMenuClosed(Menu menu) {
        super.onOptionsMenuClosed(menu);
        finish();
    }
}
