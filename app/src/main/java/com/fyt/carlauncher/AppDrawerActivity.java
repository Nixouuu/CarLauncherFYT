package com.fyt.carlauncher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.GridView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AppDrawerActivity extends Activity {

    private GridView gridApps;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        gridApps = findViewById(R.id.gridApps);
        findViewById(R.id.btnBackHome).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        loadInstalledApps();
    }

    private void loadInstalledApps() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final PackageManager pm = getPackageManager();
                Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
                mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);

                List<ResolveInfo> resolveInfos = pm.queryIntentActivities(mainIntent, 0);
                final List<AppInfo> apps = new ArrayList<>();
                String selfPackage = getPackageName();

                for (ResolveInfo ri : resolveInfos) {
                    String pkg = ri.activityInfo.packageName;
                    if (selfPackage.equals(pkg)) continue;
                    apps.add(new AppInfo(ri.loadLabel(pm).toString(), pkg, ri.loadIcon(pm)));
                }

                Collections.sort(apps, new Comparator<AppInfo>() {
                    @Override
                    public int compare(AppInfo a, AppInfo b) {
                        return a.label.compareToIgnoreCase(b.label);
                    }
                });

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || isDestroyed()) return;
                        gridApps.setAdapter(new AppListAdapter(AppDrawerActivity.this, apps, R.layout.item_app_grid));
                        gridApps.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                            @Override
                            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                                AppInfo selected = apps.get(position);
                                Intent launch = pm.getLaunchIntentForPackage(selected.packageName);
                                if (launch != null) {
                                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                    startActivity(launch);
                                } else {
                                    Toast.makeText(AppDrawerActivity.this,
                                            R.string.app_not_found, Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
