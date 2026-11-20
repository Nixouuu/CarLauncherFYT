package com.fyt.carlauncher;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

public class ApkFileProvider extends ContentProvider {

    public static final Uri UPDATE_APK_URI =
            Uri.parse("content://com.fyt.carlauncher.apkprovider/update.apk");

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File apkFile = new File(getContext().getCacheDir(), "update.apk");
        if (!apkFile.exists()) {
            throw new FileNotFoundException("APK not found");
        }
        return ParcelFileDescriptor.open(apkFile, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] sa, String so) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] sa) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] sa) { return 0; }
}
