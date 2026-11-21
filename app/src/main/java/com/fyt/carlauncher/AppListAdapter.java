package com.fyt.carlauncher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.List;

public class AppListAdapter extends BaseAdapter {
    private final LayoutInflater inflater;
    private final List<AppInfo> apps;
    private final int layoutResId;

    public AppListAdapter(Context context, List<AppInfo> apps, int layoutResId) {
        this.inflater = LayoutInflater.from(context);
        this.apps = apps;
        this.layoutResId = layoutResId;
    }

    @Override public int getCount() { return apps.size(); }
    @Override public AppInfo getItem(int position) { return apps.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ViewHolder holder;
        if (convertView == null) {
            convertView = inflater.inflate(layoutResId, parent, false);
            convertView.setSoundEffectsEnabled(false);
            holder = new ViewHolder();
            holder.icon = convertView.findViewById(R.id.imgAppIcon);
            holder.name = convertView.findViewById(R.id.tvAppName);
            holder.pkg = convertView.findViewById(R.id.tvPackageName);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        AppInfo info = apps.get(position);
        // Les icônes sont partagées via le cache : une copie légère par vue évite les conflits de bornes.
        Drawable.ConstantState cs = info.icon != null ? info.icon.getConstantState() : null;
        holder.icon.setImageDrawable(cs != null ? cs.newDrawable() : info.icon);
        holder.name.setText(info.label);
        if (holder.pkg != null) {
            holder.pkg.setText(info.packageName);
        }
        return convertView;
    }

    private static class ViewHolder {
        ImageView icon;
        TextView name;
        TextView pkg;
    }
}
