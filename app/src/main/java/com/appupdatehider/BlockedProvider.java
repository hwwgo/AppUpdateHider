package com.appupdatehider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Collections;
import java.util.Set;

/**
 * 只读 ContentProvider：把黑名单暴露给被 hook 的进程（应用商店 / 系统框架）。
 *
 * 为什么要用它：Android 10+ 的应用私有目录（/data/data/<pkg>）权限为 700，
 * 加上 SELinux 限制，模块在「商店进程 / system_server」里用 XSharedPreferences
 * 直接读文件基本会被拒绝，导致黑名单恒为空、隐藏失效。
 * ContentProvider 走 binder，只要 exported=true 就能被任意进程查询，稳定可靠。
 */
public class BlockedProvider extends ContentProvider {

    public static final String AUTHORITY = "com.appupdatehider.blocked";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/list");
    public static final String COLUMN = "pkg";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(new String[]{COLUMN});
        try {
            Set<String> blocked = getContext()
                    .getSharedPreferences(SettingsActivity.PREF_NAME, 0)
                    .getStringSet(SettingsActivity.KEY_BLOCKED, Collections.<String>emptySet());
            if (blocked != null) {
                for (String pkg : blocked) {
                    cursor.addRow(new Object[]{pkg});
                }
            }
        } catch (Throwable ignored) {
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.updateblocker.blocked";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
