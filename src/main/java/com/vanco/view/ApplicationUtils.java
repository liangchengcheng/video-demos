package com.vanco.view;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

public class ApplicationUtils {

    /**
     * 返回当前的应用是否处于前台显示状态 不需要android.permission.GET_TASKS权限
     * http://zengrong.net/post/1680.htm
     */
    public static boolean isTopActivity(Context context, String packageName) {
        ActivityManager am = (ActivityManager) context.getApplicationContext()
                .getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> list = am
                .getRunningAppProcesses();
        if (list.size() == 0)
            return false;
        for (ActivityManager.RunningAppProcessInfo process : list) {
            if (process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                    && process.processName.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

}
