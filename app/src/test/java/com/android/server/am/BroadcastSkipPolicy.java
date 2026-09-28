package com.android.server.am;
public final class BroadcastSkipPolicy {
    public int checks;
    public String shouldSkipMessage(BroadcastRecord record,int index) { checks++; return "permission denied"; }
}
