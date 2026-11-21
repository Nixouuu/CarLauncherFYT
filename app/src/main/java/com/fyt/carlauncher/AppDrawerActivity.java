package com.fyt.carlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.widget.GridView;
import android.widget.Toast;

public class AppDrawerActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        final ClickSound sound = ClickSound.get(this);
        final GridView gridApps = findViewById(R.id.gridApps);

        findViewById(R.id.btnBackHome).setOnClickListener(v -> {
            sound.play();
            finish();
        });

        Apps.get(this, apps -> {
            if (isFinishing() || isDestroyed()) return;
            gridApps.setAdapter(new AppListAdapter(this, apps, R.layout.item_app_grid));
            gridApps.setOnItemClickListener((parent, view, position, id) -> {
                sound.play();
                if (!Apps.launch(this, apps.get(position).packageName)) {
                    Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
