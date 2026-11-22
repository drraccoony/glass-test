package dev.glassrevival.companion;

/** Human-readable link status, shared by the app screen and the ongoing notification. */
final class StatusText {
    private StatusText() {}

    static String title(LinkManager link) {
        switch (link.state()) {
            case CONNECTED:
                return "Connected to " + link.glassName();
            case CONNECTING:
                return "Connecting to Glass…";
            case NOT_CONFIGURED:
                return "Set up your Glass";
            case NO_PERMISSION:
                return "Bluetooth permission needed";
            case BLUETOOTH_OFF:
                return "Bluetooth is off";
            case REJECTED:
                return "Glass refused the connection";
            case OFF:
            default:
                return "Forwarding is off";
        }
    }

    static String detail(LinkManager link) {
        if (link.state() == LinkManager.State.CONNECTED) {
            int battery = link.glassBattery();
            if (battery < 0) return "Notifications are going to Glass";
            return "Glass battery " + battery + "%" + (link.glassCharging() ? ", charging" : "");
        }
        String d = link.detail();
        if (!d.isEmpty()) return d;
        switch (link.state()) {
            case NOT_CONFIGURED:
                return "Enter the Glass address and pair code below";
            case OFF:
                return "Turn on forwarding below";
            default:
                return "";
        }
    }
}
