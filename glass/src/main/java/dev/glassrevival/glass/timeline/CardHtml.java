package dev.glassrevival.glass.timeline;

/**
 * HTML for a notification card in the Glass timeline. Glass renders it in a WebView with
 * its own stylesheet (glass://GLASS_CSS, the Mirror API card styles), so this uses the
 * Mirror "author" layout: a round picture, the sender, the app, then the message.
 * Glass adds the time footer itself.
 *
 * <pre>
 *  (o)  Alex                          sender, or the app when there's no title
 *       [icon] Messages               app, grey
 *  Are we still on for lunch?         auto-sized to fit
 *  Bring the slides too
 *                         2 mins ago  (Glass)
 * </pre>
 */
final class CardHtml {
    private CardHtml() {}

    static String build(NativeTimeline.Card card) {
        String name = !card.title.isEmpty() ? card.title : card.app;
        String subtitle = !card.title.isEmpty() ? card.app : "";
        // The big picture is the sender's photo when the phone has one, else the app icon.
        String picture = card.avatarPng != null ? card.avatarPng : card.iconPng;
        boolean pictureIsAppIcon = card.avatarPng == null;

        StringBuilder h = new StringBuilder(1024);
        h.append("<article class=\"author\">");
        h.append(picture == null ? "<header style=\"padding-left:0\">" : "<header>");
        if (picture != null) {
            h.append("<img src=\"data:image/png;base64,").append(picture).append('"');
            // App icons have their own shape; only photos get the circle crop.
            if (pictureIsAppIcon) h.append(" style=\"border-radius:0\"");
            h.append('>');
        }
        h.append("<h1>").append(escape(name)).append("</h1>");
        if (!subtitle.isEmpty()) {
            h.append("<h2>");
            // With a photo up top, a small app icon says which app it came from.
            if (!pictureIsAppIcon && card.iconPng != null) {
                h.append("<img src=\"data:image/png;base64,").append(card.iconPng)
                        .append("\" style=\"width:24px;height:24px;margin-right:8px;vertical-align:-5px\">");
            }
            h.append(escape(subtitle)).append("</h2>");
        }
        h.append("</header>");
        String body = bodyHtml(card);
        if (!body.isEmpty()) {
            h.append("<section><p class=\"text-auto-size\">").append(body).append("</p></section>");
        }
        h.append("</article>");
        return h.toString();
    }

    /**
     * One line per message. Conversation lines look like "Sam: hi". The sender part is
     * greyed, and dropped when it's the card's own title (a one-to-one chat).
     */
    private static String bodyHtml(NativeTimeline.Card card) {
        if (card.text.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String line : card.text.split("\n")) {
            if (line.trim().isEmpty()) continue;
            if (out.length() > 0) out.append("<br>");
            int colon = line.indexOf(": ");
            if (colon > 0 && colon <= 40) {
                String sender = line.substring(0, colon);
                String message = line.substring(colon + 2);
                if (sender.equals(card.title)) {
                    out.append(escape(message));
                } else {
                    out.append("<span class=\"muted\">").append(escape(sender)).append("</span> ")
                            .append(escape(message));
                }
            } else {
                out.append(escape(line));
            }
        }
        return out.toString();
    }

    static String escape(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }
}
