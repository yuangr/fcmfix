package com.android.server.am;
import android.content.Intent;
public final class BroadcastController {
    public int seenAppOp,seenUserId,calls;
    // Android 16 binder entry: appOp at 11, userId at 15, without Java parameter names.
    public int broadcastIntentWithFeature(Object caller,String featureId,Intent intent,String resolvedType,
            Object resultTo,int resultCode,String resultData,Object resultExtras,String[] permissions,
            String[] excludedPermissions,String[] excludedPackages,int appOp,Object options,boolean serialized,
            boolean sticky,int userId) {
        seenAppOp=appOp; seenUserId=userId; calls++; return 17;
    }
}
