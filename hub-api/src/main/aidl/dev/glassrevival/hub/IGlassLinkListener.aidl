package dev.glassrevival.hub;

/** Callbacks from the hub. One-way, so a slow app never holds up the link. */
oneway interface IGlassLinkListener {
    void onMessage(String json);

    void onConnectionChanged(boolean connected);
}
