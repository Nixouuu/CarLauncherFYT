package com.fyt.carlauncher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Panneau « Où aller ? » : recherche d'adresse Stadia + destinations récentes. */
final class PlaceSearch {

    private static final String KEY_RECENTS = "nav_recents";
    private static final int MAX_RECENTS = 8;

    private final Activity activity;
    private final SharedPreferences prefs;
    private final ClickSound sound;
    private final LiveMap liveMap;
    private final Navigator navigator;
    private final View panel;
    private final EditText input;
    private final TextView hint;
    private final Adapter adapter = new Adapter();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int requestSeq;

    PlaceSearch(Activity activity, SharedPreferences prefs, ClickSound sound, LiveMap liveMap, Navigator navigator) {
        this.activity = activity;
        this.prefs = prefs;
        this.sound = sound;
        this.liveMap = liveMap;
        this.navigator = navigator;
        panel = activity.findViewById(R.id.searchPanel);
        input = activity.findViewById(R.id.etPlaceSearch);
        hint = activity.findViewById(R.id.tvSearchHint);
        ListView list = activity.findViewById(R.id.listPlaces);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            sound.play();
            go(adapter.items.get(pos));
        });

        activity.findViewById(R.id.btnSearchClose).setOnClickListener(v -> {
            sound.play();
            close();
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacksAndMessages(null);
                handler.postDelayed(() -> search(s.toString()), 350);
            }
        });
        input.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                handler.removeCallbacksAndMessages(null);
                search(input.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    boolean isOpen() {
        return panel.getVisibility() == View.VISIBLE;
    }

    void open() {
        panel.setVisibility(View.VISIBLE);
        input.setText("");
        showRecents();
        if (!StadiaApi.hasKey()) hint.setText(R.string.nav_no_key);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    void close() {
        requestSeq++;   // une recherche encore en route ne remplira pas le panneau à sa prochaine ouverture
        handler.removeCallbacksAndMessages(null);
        hideKeyboard();
        panel.setVisibility(View.GONE);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void search(String text) {
        final String q = text.trim();
        if (q.length() < 3) {
            showRecents();
            return;
        }
        if (!StadiaApi.hasKey()) {
            hint.setText(R.string.nav_no_key);
            return;
        }
        final int seq = ++requestSeq;
        hint.setText(R.string.nav_searching);
        Location here = liveMap.lastLocation();
        double lat = here != null ? here.getLatitude() : 45.5017;
        double lon = here != null ? here.getLongitude() : -73.5673;
        StadiaApi.autocomplete(q, lat, lon, (places, error) -> {
            if (seq != requestSeq || !isOpen()) return;
            if (places == null) {
                hint.setText(R.string.nav_search_error);
                return;
            }
            hint.setText(places.isEmpty() ? R.string.nav_no_result : R.string.nav_results);
            adapter.items = places;
            adapter.notifyDataSetChanged();
        });
    }

    private void go(StadiaApi.Place p) {
        saveRecent(p);
        close();
        navigator.navigateTo(p.name, p.lat, p.lon);
    }

    // ---------------------------------------------------------------- récents

    private void showRecents() {
        requestSeq++;   // un résultat de recherche arrivé en retard ne remplace plus les récents
        adapter.items = loadRecents();
        adapter.notifyDataSetChanged();
        hint.setText(adapter.items.isEmpty() ? R.string.nav_type_address : R.string.nav_recent);
    }

    private List<StadiaApi.Place> loadRecents() {
        List<StadiaApi.Place> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_RECENTS, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new StadiaApi.Place(o.getString("n"), o.optString("l"), o.getDouble("a"), o.getDouble("o"), -1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void saveRecent(StadiaApi.Place p) {
        List<StadiaApi.Place> list = loadRecents();
        JSONArray a = new JSONArray();
        try {
            a.put(new JSONObject().put("n", p.name).put("l", p.label).put("a", p.lat).put("o", p.lon));
            for (StadiaApi.Place r : list) {
                if (a.length() >= MAX_RECENTS) break;
                if (Math.abs(r.lat - p.lat) < 1e-5 && Math.abs(r.lon - p.lon) < 1e-5) continue;
                a.put(new JSONObject().put("n", r.name).put("l", r.label).put("a", r.lat).put("o", r.lon));
            }
        } catch (Exception ignored) {
        }
        prefs.edit().putString(KEY_RECENTS, a.toString()).apply();
    }

    // ---------------------------------------------------------------- liste

    private final class Adapter extends BaseAdapter {
        List<StadiaApi.Place> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(activity).inflate(R.layout.item_place, parent, false);
                view.setSoundEffectsEnabled(false);
            }
            final StadiaApi.Place p = items.get(position);
            ((TextView) view.findViewById(R.id.tvPlaceName)).setText(p.name);
            String sub = p.label;
            if (p.distanceKm > 0) sub = Navigator.formatDistance(p.distanceKm * 1000) + " · " + sub;
            ((TextView) view.findViewById(R.id.tvPlaceLabel)).setText(sub);
            view.findViewById(R.id.btnPlaceWaze).setOnClickListener(v -> {
                sound.play();
                saveRecent(p);
                close();
                Navigator.openInWaze(activity, p.lat, p.lon);
            });
            return view;
        }
    }
}
