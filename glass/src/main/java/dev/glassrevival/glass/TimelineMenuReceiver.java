package dev.glassrevival.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import dev.glassrevival.glass.timeline.NativeTimeline;

/**
 * "Reply" on a GlassLink card in the Glass timeline. The home screen broadcasts the
 * card's id. This opens GlassLink on that notification with its reply menu showing
 * (voice reply, quick replies, dismiss).
 */
public class TimelineMenuReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(final Context context, Intent intent) {
        if (!NativeTimeline.ACTION_REPLY.equals(intent.getAction())) return;
        final String cardId = intent.getStringExtra("itemId");
        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                String key = NativeTimeline.keyForCard(context, cardId);
                if (key == null) {
                    Log.w(LinkService.TAG, "Reply for unknown timeline card " + cardId);
                    return;
                }
                context.startActivity(new Intent(context, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_REPLY_KEY, key));
            } finally {
                result.finish();
            }
        }, "GlassLink-timeline-reply").start();
    }
}
