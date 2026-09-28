package com.android.server.am;
import android.content.Intent;
public final class BroadcastRecord {
    public Intent intent; public int callingUid; public int userId;
    public BroadcastRecord(Intent intent,int uid,int userId) { this.intent=intent; callingUid=uid; this.userId=userId; }
}
