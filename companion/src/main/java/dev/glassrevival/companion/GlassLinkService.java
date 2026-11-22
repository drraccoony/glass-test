package dev.glassrevival.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** Keeps the app alive in the foreground while forwarding is on, and shows link status. */
public class GlassLinkService extends Service implements LinkManager.Listener {

    private static final String CHANNEL = "link";
    private static final int NOTIFICATION_ID = 1;

    static void startIfEnabled(Context context) {
        if (!CompanionApp.prefs(context).enabled()) return;
        try {
            context.startForegroundService(new Intent(context, GlassLinkService.class));
        } catch (RuntimeException e) {
            // Android 12+ refuses some background starts. The app or boot receiver will retry.
            Log.w(LinkManager.TAG, "Couldn't start link service: " + e);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, GlassLinkService.class));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinkManager link;
    private NotificationManager nm;
    private boolean receiverRegistered;

    @Override
    public void onCreate() {
        super.onCreate();
        link = CompanionApp.link(this);
        nm = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.channel_description));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification());
            }
        } catch (RuntimeException e) {
            // Missing Bluetooth permission on Android 14+ lands here.
            Log.w(LinkManager.TAG, "Couldn't enter foreground: " + e);
            stopSelf();
            return;
        }
        registerReceiver(bluetoothReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
        receiverRegistered = true;
        link.addListener(this);
        link.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        link.removeListener(this);
        link.stop();
        if (receiverRegistered) unregisterReceiver(bluetoothReceiver);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onLinkChanged() {
        main.post(() -> nm.notify(NOTIFICATION_ID, buildNotification()));
    }

    private Notification buildNotification() {
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_glass)
                .setContentTitle(StatusText.title(link))
                .setContentText(StatusText.detail(link))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) link.poke();
            if (state == BluetoothAdapter.STATE_TURNING_OFF) link.reconnectNow();
        }
    };
}
