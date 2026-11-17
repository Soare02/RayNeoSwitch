package dev.rayneo.switcher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;

import java.util.Arrays;

public final class ConfigProvider extends ContentProvider {
    private ConfigStore store;

    @Override
    public boolean onCreate() {
        store = new ConfigStore(getContext());
        return true;
    }

    @Override
    public Bundle call(String method, String argument, Bundle extras) {
        int uid = Binder.getCallingUid();
        boolean owner = uid == Process.myUid();
        boolean root = uid == 0;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        boolean target = packages != null && Arrays.asList(packages).contains(ConfigStore.TARGET_PACKAGE);
        Bundle result = new Bundle();
        try {
            switch (method) {
                case "get_config":
                    if (!(owner || target)) {
                        throw new SecurityException("Caller cannot read model credentials");
                    }
                    return ConfigStore.currentBundle(getContext());
                case "save_config":
                    if (!(owner || root)) {
                        throw new SecurityException("Caller cannot change model settings");
                    }
                    if (extras == null) {
                        throw new IllegalArgumentException("缺少配置参数");
                    }
                    store.save(ConfigStore.Config.fromBundle(extras));
                    result.putBoolean("ok", true);
                    return result;
                case "get_status":
                    if (!(owner || root)) {
                        throw new SecurityException("Caller cannot read module status");
                    }
                    return store.status();
                case "report_status":
                    if (!target || extras == null) {
                        throw new SecurityException("Caller cannot report module status");
                    }
                    store.status(extras.getString("message", ""), extras.getString("model", ""),
                            Binder.getCallingPid(), extras.getString("search_mode", SearchConfig.OFF),
                            extras.getString("search_name", "关闭联网搜索"));
                    result.putBoolean("ok", true);
                    return result;
                default:
                    throw new IllegalArgumentException("Unknown method");
            }
        } catch (SecurityException exception) {
            throw exception;
        } catch (Exception exception) {
            result.putBoolean("ok", false);
            result.putString("error", exception.getClass().getSimpleName());
            return result;
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.dev.rayneo.switcher";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
