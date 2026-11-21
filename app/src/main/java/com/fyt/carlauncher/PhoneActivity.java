package com.fyt.carlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Écran Téléphone intégré : clavier + contacts de l'iPhone (synchronisés par le Bluetooth FYT). */
public class PhoneActivity extends Activity implements FytBt.Listener {

    private FytBt bt;
    private ClickSound sound;
    private TextView tvNumber, tvStatus, tvEmpty;
    private EditText etSearch;
    private final StringBuilder number = new StringBuilder();
    private final ContactAdapter adapter = new ContactAdapter();
    private boolean syncRequested;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone);
        bt = FytBt.get(this);
        bt.connect();
        sound = ClickSound.get(this);

        tvNumber = findViewById(R.id.tvDialNumber);
        tvStatus = findViewById(R.id.tvPhoneStatus);
        tvEmpty = findViewById(R.id.tvContactsEmpty);
        etSearch = findViewById(R.id.etContactSearch);
        ListView list = findViewById(R.id.listContacts);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            sound.play();
            call(adapter.items.get(pos).number);
        });

        Keypad.build(this, (LinearLayout) findViewById(R.id.dialKeypad), 62, sound, key -> {
            if (number.length() < 24) number.append(key);
            tvNumber.setText(number);
        });

        tap(R.id.btnPhoneHome, this::goHome);
        tap(R.id.btnDialDelete, () -> {
            if (number.length() > 0) number.setLength(number.length() - 1);
            tvNumber.setText(number);
        });
        findViewById(R.id.btnDialDelete).setOnLongClickListener(v -> {
            number.setLength(0);
            tvNumber.setText(number);
            return true;
        });
        tap(R.id.btnDialCall, () -> {
            if (number.length() > 0) call(number.toString());
            else if (bt.redial()) Toast.makeText(this, R.string.phone_redial, Toast.LENGTH_SHORT).show();
        });
        tap(R.id.btnSyncContacts, this::syncContacts);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refreshContacts(); }
        });
    }

    private void tap(int id, Runnable action) {
        findViewById(id).setOnClickListener(v -> {
            sound.play();
            action.run();
        });
    }

    private void goHome() {
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        goHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        bt.addListener(this);
        render();
        refreshContacts();
        // Première visite : on demande le répertoire à l'iPhone.
        if (bt.contacts.isEmpty() && bt.isPhoneConnected() && !syncRequested) syncContacts();
    }

    @Override
    protected void onPause() {
        super.onPause();
        bt.removeListener(this);
    }

    @Override
    public void onFytUpdate(int code) {
        if (code == FytBt.U_BOOK || code == FytBt.U_PBAP_STATE) refreshContacts();
        render();
    }

    private void syncContacts() {
        syncRequested = true;
        if (bt.downloadContacts()) {
            Toast.makeText(this, R.string.phone_syncing, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.phone_bt_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    private void call(String n) {
        if (!bt.isReady()) {
            Toast.makeText(this, R.string.phone_bt_unavailable, Toast.LENGTH_LONG).show();
            return;
        }
        if (!bt.isPhoneConnected()) {
            Toast.makeText(this, R.string.phone_not_connected, Toast.LENGTH_LONG).show();
            return;
        }
        bt.dial(n);
    }

    private void render() {
        if (!bt.isReady()) {
            tvStatus.setText(getString(R.string.phone_status_service, bt.status()));
        } else if (bt.isPhoneConnected()) {
            tvStatus.setText(getString(R.string.phone_status_connected,
                    bt.phoneName != null ? bt.phoneName : getString(R.string.phone_title)));
        } else {
            tvStatus.setText(R.string.phone_not_connected);
        }
    }

    private void refreshContacts() {
        String q = normalize(etSearch.getText().toString());
        List<FytBt.Contact> out = new ArrayList<>();
        for (FytBt.Contact c : bt.sortedContacts()) {
            if (q.isEmpty() || normalize(c.name).contains(q) || c.number.replaceAll("\\D", "").contains(q)) out.add(c);
        }
        adapter.items = out;
        adapter.notifyDataSetChanged();
        tvEmpty.setVisibility(out.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}", "").toLowerCase(Locale.FRENCH).trim();
    }

    private final class ContactAdapter extends BaseAdapter {
        List<FytBt.Contact> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = LayoutInflater.from(PhoneActivity.this).inflate(R.layout.item_contact, parent, false);
                view.setSoundEffectsEnabled(false);
            }
            FytBt.Contact c = items.get(position);
            ((TextView) view.findViewById(R.id.tvContactName)).setText(c.name);
            ((TextView) view.findViewById(R.id.tvContactNumber)).setText(c.number);
            ((TextView) view.findViewById(R.id.tvContactInitial)).setText(
                    TextUtils.isEmpty(c.name) ? "?" : c.name.substring(0, 1).toUpperCase(Locale.FRENCH));
            return view;
        }
    }
}
