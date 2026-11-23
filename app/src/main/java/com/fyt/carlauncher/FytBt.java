package com.fyt.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pont vers le Bluetooth interne des autoradios FYT.
 *
 * Sur FYT, le téléphone Bluetooth (HFP) et la musique Bluetooth (A2DP/AVRCP) passent par une puce BT
 * gérée par le service système {@code com.syu.ms}, pas par la pile Bluetooth d'Android. Ce service expose
 * une interface Binder ({@code com.syu.ms/app.ToolkitService}, action {@code com.syu.ms.toolkit}) qu'on
 * appelle ici avec des transactions brutes, sans dépendance.
 *
 * Protocole (non documenté par FYT) reconstitué à partir de projets ouverts :
 *  - github.com/Pacjonek/fyt-dialer (Apache-2.0) : transactions IRemoteToolkit / IRemoteModule / IModuleCallback ;
 *  - github.com/vasyl91/FYT-Launcher-Mod : constantes FinalBt (C_* commandes, U_* mises à jour) ;
 *  - github.com/Tormod22/bilstereo-launcher : source audio BT (module principal, cmd 0 / [3]).
 *
 * Tout est protégé : si le service n'existe pas (autre autoradio), rien ne plante, {@link #isReady()} reste faux.
 */
final class FytBt {

    // ---- Binder
    private static final String PKG = "com.syu.ms";
    private static final String SERVICE = "app.ToolkitService";
    private static final String ACTION = "com.syu.ms.toolkit";
    private static final String D_TOOLKIT = "com.syu.ipc.IRemoteToolkit";
    private static final String D_MODULE = "com.syu.ipc.IRemoteModule";
    private static final String D_CALLBACK = "com.syu.ipc.IModuleCallback";
    private static final int T_GET_MODULE = 1;
    private static final int T_CMD = 1;
    private static final int T_REGISTER = 3;
    private static final int T_UNREGISTER = 4;
    private static final int T_UPDATE = 1;

    // ---- Modules
    private static final int M_MAIN = 0;
    private static final int M_BT = 2;
    private static final int M_SOUND = 4;
    // Mises à jour du module son (FinalSound.U_*) : volume réel de l'autoradio (celui de la barre d'état)
    static final int U_SOUND_VOL = 1002, U_SOUND_MUTE = 1003;   // codes internes = 1000 + code FYT

    // ---- Commandes BT (FinalBt.C_*)
    private static final int C_BTAV_PREV = 0, C_BTAV_NEXT = 1, C_BTAV_PLAYPAUSE = 2, C_BTAV_PAUSE = 3;
    private static final int C_KEY = 6, C_DIAL = 7, C_REDIAL = 8, C_PICKUP = 9, C_HANG = 10, C_REJECT_RING = 11;
    private static final int C_HFP = 14, C_DOWNLOAD_BOOK = 26, C_BTAV_PLAY = 27;

    // ---- Mises à jour BT (FinalBt.U_*)
    static final int U_BTAV_TITLE = 0, U_BTAV_ARTIST = 1, U_BTAV_TOTAL_TIME = 2;
    static final int U_PHONE_NAME = 7, U_PHONE_NUMBER = 8, U_PHONE_STATE = 9, U_TALK_TIME = 12;
    static final int U_BTAV_PLAY_STATE = 13, U_HFP = 18, U_BOOK = 22, U_BTAV_ALBUM = 26;
    static final int U_BTAV_PLAY_TIME = 28, U_PBAP_STATE = 29;
    private static final int[] WATCHED = {
            U_BTAV_TITLE, U_BTAV_ARTIST, U_BTAV_TOTAL_TIME, U_PHONE_NAME, U_PHONE_NUMBER, U_PHONE_STATE,
            U_TALK_TIME, U_BTAV_PLAY_STATE, U_HFP, U_BOOK, U_BTAV_ALBUM, U_BTAV_PLAY_TIME, U_PBAP_STATE};

    // ---- États du téléphone (FinalBt.PHONE_STATE_*)
    static final int PHONE_DISCONNECTED = 0, PHONE_LINK = 1, PHONE_CONNECTED = 2;
    static final int PHONE_DIAL = 3, PHONE_RING = 4, PHONE_TALK = 5, PHONE_PAIR = 6;

    interface Listener {
        void onFytUpdate(int code);
    }

    static final class Contact {
        final String name;
        final String number;

        Contact(String name, String number) {
            this.name = name;
            this.number = number;
        }
    }

    private static FytBt instance;

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final SimpleDateFormat logTime = new SimpleDateFormat("HH:mm:ss", Locale.FRENCH);

    private IBinder toolkit, btModule, mainModule, soundModule;
    private boolean binding;
    private String status = "non connecté";

    // ---- État exposé (lu sur le thread principal)
    int phoneState = -1;
    /** Augmente à chaque nouvel appel (entrant ou sortant) : sert à l'écran d'appel à repartir de zéro. */
    int callId;
    private boolean resetBookOnNextEntry;
    String phoneName, callNumber;
    int talkSeconds;
    long callStartedAt;
    boolean audioOnPhone;
    String btavTitle, btavArtist;
    boolean btavPlaying;
    int btavPositionMs, btavDurationMs;
    final Map<String, Contact> contacts = new LinkedHashMap<>();
    /** Volume de l'autoradio (-1 = inconnu) et son maximum observé. */
    int volume = -1, volumeMax = 40;
    boolean muted;

    static synchronized FytBt get(Context context) {
        if (instance == null) instance = new FytBt(context.getApplicationContext());
        return instance;
    }

    private FytBt(Context app) {
        this.app = app;
    }

    // ---------------------------------------------------------------- connexion

    /** Résultat de chaque tentative de connexion (écran Diagnostic et rapport GitHub). */
    final List<String> bindAttempts = new ArrayList<>();

    /**
     * Se connecte au service FYT (sans effet si déjà connecté ou absent).
     *
     * La classe du service s'appelle littéralement « app.ToolkitService » dans le paquet com.syu.ms
     * (notation com.syu.ms/app.ToolkitService) : ce n'est PAS « com.syu.ms.app.ToolkitService ».
     * On essaie d'abord le nom exact, puis des variantes, et on note le résultat de chacune.
     */
    void connect() {
        if (toolkit != null || binding) return;
        main.removeCallbacks(retryConnect);
        bindAttempts.clear();
        tryCandidate(0);
    }

    /** Sans réponse du service dans ce délai, on abandonne cette variante et on essaie la suivante. */
    private static final long BIND_TIMEOUT_MS = 5000;
    /** Service introuvable : nouvel essai complet plus tard (le service FYT peut démarrer après le launcher). */
    private static final long RETRY_CONNECT_MS = 60_000;
    private static final int MAX_CONNECT_ROUNDS = 10;
    private int candidateIndex, connectRounds;

    private final Runnable retryConnect = this::connect;

    private final Runnable bindTimeout = () -> {
        if (toolkit != null || !binding) return;
        bindAttempts.add("  pas de réponse en " + BIND_TIMEOUT_MS / 1000 + " s : variante suivante");
        note("bindService : pas de réponse, variante suivante");
        try {
            // Noms qualifiés : « connection » est déclaré plus bas et « app » est affecté dans le constructeur.
            FytBt.this.app.unbindService(FytBt.this.connection);
        } catch (Exception ignored) {
        }
        binding = false;
        tryCandidate(candidateIndex + 1);
    };

    private Intent[] candidates() {
        List<Intent> list = new ArrayList<>();
        list.add(new Intent(ACTION).setComponent(new ComponentName(PKG, SERVICE)));
        list.add(new Intent(ACTION).setComponent(new ComponentName(PKG, PKG + "." + SERVICE)));
        // Services qui déclarent l'action FYT, quel que soit leur nom réel sur cet autoradio.
        try {
            for (android.content.pm.ResolveInfo ri : app.getPackageManager().queryIntentServices(new Intent(ACTION), 0)) {
                if (ri.serviceInfo == null) continue;
                list.add(new Intent(ACTION).setComponent(
                        new ComponentName(ri.serviceInfo.packageName, ri.serviceInfo.name)));
            }
        } catch (Exception ignored) {
        }
        list.add(new Intent(ACTION).setPackage(PKG));
        return list.toArray(new Intent[0]);
    }

    /**
     * Essaie les variantes à partir de {@code from}. bindService peut répondre « OK » sans que le service
     * se connecte jamais : un délai fait alors passer à la variante suivante (avant, tout restait bloqué
     * sur « connexion… »).
     */
    private void tryCandidate(int from) {
        Intent[] candidates = candidates();
        for (int i = from; i < candidates.length; i++) {
            Intent intent = candidates[i];
            String what = intent.getComponent() != null ? intent.getComponent().flattenToShortString()
                    : "action " + ACTION + " + paquet " + PKG;
            try {
                binding = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                bindAttempts.add(what + " → " + (binding ? "OK" : "refusé (false)"));
            } catch (Exception e) {
                binding = false;
                bindAttempts.add(what + " → " + e.getClass().getSimpleName() + " : " + e.getMessage());
            }
            note("bindService " + bindAttempts.get(bindAttempts.size() - 1));
            if (binding) {
                candidateIndex = i;
                status = "connexion…";
                main.removeCallbacks(bindTimeout);
                main.postDelayed(bindTimeout, BIND_TIMEOUT_MS);
                return;
            }
        }
        status = "service FYT absent";
        if (++connectRounds < MAX_CONNECT_ROUNDS) {
            main.removeCallbacks(retryConnect);
            main.postDelayed(retryConnect, RETRY_CONNECT_MS);
        }
    }

    boolean isReady() {
        return btModule != null;
    }

    String status() {
        return status;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            main.removeCallbacks(bindTimeout);
            binding = false;
            connectRounds = 0;
            toolkit = service;
            btModule = getModule(M_BT);
            mainModule = getModule(M_MAIN);
            soundModule = getModule(M_SOUND);
            status = btModule != null ? "connecté (BT" + (mainModule != null ? " + principal)" : ")") : "connecté, module BT introuvable";
            note(status);
            if (btModule != null) {
                for (int code : WATCHED) register(btModule, code, callback);
            }
            if (soundModule != null) {
                register(soundModule, 2, soundCallback);
                register(soundModule, 3, soundCallback);
            }
            dispatch(-1);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            toolkit = btModule = mainModule = soundModule = null;
            status = "déconnecté";
            note(status);
            dispatch(-1);
            main.postDelayed(FytBt.this::connect, 3000);
        }
    };

    private IBinder getModule(int id) {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(D_TOOLKIT);
            data.writeInt(id);
            toolkit.transact(T_GET_MODULE, data, reply, 0);
            reply.readException();
            return reply.readStrongBinder();
        } catch (Exception e) {
            note("getRemoteModule(" + id + ") : " + e.getClass().getSimpleName());
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void register(IBinder module, int code, Binder cb) {
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(D_MODULE);
            data.writeStrongBinder(cb);
            data.writeInt(code);
            data.writeInt(1);   // 1 = envoyer tout de suite la valeur actuelle
            module.transact(T_REGISTER, data, null, IBinder.FLAG_ONEWAY);
        } catch (Exception e) {
            note("register(" + code + ") : " + e.getClass().getSimpleName());
        } finally {
            data.recycle();
        }
    }

    private boolean cmd(IBinder module, int code, int[] ints, String[] strs) {
        if (module == null) {
            note("commande " + code + " ignorée : module absent");
            return false;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(D_MODULE);
            data.writeInt(code);
            data.writeIntArray(ints);
            data.writeFloatArray(null);
            data.writeStringArray(strs);
            module.transact(T_CMD, data, null, IBinder.FLAG_ONEWAY);
            note("→ cmd " + code + " " + Arrays.toString(ints) + " " + Arrays.toString(strs));
            return true;
        } catch (Exception e) {
            note("cmd(" + code + ") : " + e.getClass().getSimpleName());
            return false;
        } finally {
            data.recycle();
        }
    }

    /** Callback appelé par com.syu.ms (thread Binder) : on recopie puis on traite sur le thread principal. */
    private final Binder callback = new Binder() {
        {
            attachInterface(null, D_CALLBACK);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == T_UPDATE) {
                try {
                    data.enforceInterface(D_CALLBACK);
                    final int u = data.readInt();
                    final int[] ints = data.createIntArray();
                    data.createFloatArray();
                    final String[] strs = data.createStringArray();
                    main.post(() -> onUpdate(u, ints, strs));
                } catch (Exception ignored) {
                }
                return true;
            }
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(D_CALLBACK);
                return true;
            }
            return false;
        }
    };

    /** Callback du module son : volume et sourdine de l'autoradio. */
    private final Binder soundCallback = new Binder() {
        {
            attachInterface(null, D_CALLBACK);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code != T_UPDATE) return super.onTransact(code, data, reply, flags);
            try {
                data.enforceInterface(D_CALLBACK);
                final int u = data.readInt();
                final int[] ints = data.createIntArray();
                main.post(() -> onSoundUpdate(u, ints));
            } catch (Exception ignored) {
            }
            return true;
        }
    };

    private void onSoundUpdate(int u, int[] ints) {
        if (ints == null || ints.length == 0) return;
        note("← son u" + u + " " + Arrays.toString(ints));
        if (u == 2) {
            volume = Math.max(0, ints[0]);
            if (volume > volumeMax) volumeMax = volume;
            dispatch(U_SOUND_VOL);
        } else if (u == 3) {
            muted = ints[0] != 0;
            dispatch(U_SOUND_MUTE);
        }
    }

    // ---------------------------------------------------------------- mises à jour

    private void onUpdate(int u, int[] ints, String[] strs) {
        // Progression de la musique et durée d'appel : une mise à jour par seconde, elles noyaient le journal.
        if (u != U_BTAV_PLAY_TIME && u != U_TALK_TIME) {
            note("← u" + u + " " + Arrays.toString(ints) + " " + Arrays.toString(strs));
        }
        int i0 = ints != null && ints.length > 0 ? ints[0] : Integer.MIN_VALUE;
        switch (u) {
            case U_PHONE_STATE:
                if (i0 >= 0 && i0 <= 6) {
                    boolean wasInCall = isInCall();
                    int previous = phoneState;
                    phoneState = i0;
                    if (i0 == PHONE_TALK && previous != PHONE_TALK) callStartedAt = SystemClock.elapsedRealtime();
                    if (isInCall() && !wasInCall) callId++;
                    if (!isInCall()) {
                        talkSeconds = 0;
                        audioOnPhone = false;
                    }
                    // Téléphone déconnecté : ses contacts ne doivent pas rester mélangés à ceux du suivant.
                    if (i0 == PHONE_DISCONNECTED && previous >= PHONE_CONNECTED) contacts.clear();
                }
                break;
            case U_PHONE_NAME:
                phoneName = pickString(strs);
                break;
            case U_PHONE_NUMBER:
                callNumber = pickString(strs);
                break;
            case U_TALK_TIME:
                if (i0 >= 0) talkSeconds = i0;
                break;
            case U_HFP:
                // 0 = audio sur le téléphone, 1 = audio sur l'autoradio (valeur à confirmer : on suit le dernier basculement)
                if (i0 != Integer.MIN_VALUE) audioOnPhone = i0 == 0;
                break;
            case U_BTAV_TITLE:
                btavTitle = pickString(strs);
                break;
            case U_BTAV_ARTIST:
                btavArtist = pickString(strs);
                break;
            case U_BTAV_PLAY_STATE:
                if (i0 != Integer.MIN_VALUE) btavPlaying = i0 == 1;
                break;
            case U_BTAV_PLAY_TIME:
                if (i0 >= 0) btavPositionMs = i0;
                break;
            case U_BTAV_TOTAL_TIME:
                if (i0 >= 0) btavDurationMs = i0;
                break;
            case U_BOOK:
                if (resetBookOnNextEntry) {
                    // Première fiche d'une nouvelle synchronisation : on repart d'une liste vide.
                    resetBookOnNextEntry = false;
                    contacts.clear();
                }
                parseBookEntry(strs);
                break;
            default:
                break;
        }
        dispatch(u);
    }

    /** Variante « deux téléphones » : strs[0] = index « 0 »/« 1 », strs[1] = valeur. */
    private static String pickString(String[] strs) {
        if (strs == null || strs.length == 0) return null;
        String v = strs.length >= 2 && ("0".equals(trim(strs[0])) || "1".equals(trim(strs[0]))) ? strs[1] : strs[0];
        v = trim(v);
        return v == null || v.isEmpty() ? null : v;
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /** Entrée de répertoire : on cherche un nom et un numéro dans les chaînes reçues. */
    private void parseBookEntry(String[] strs) {
        if (strs == null) return;
        String name = null, number = null;
        for (String s : strs) {
            String t = trim(s);
            if (t == null || t.isEmpty()) continue;
            if (number == null && t.replaceAll("[\\s+()\\-.]", "").matches("\\d{3,}")) number = t;
            else if (name == null && !t.matches("\\d{1,2}")) name = t;
        }
        if (number == null) return;
        if (name == null) name = number;
        contacts.put(name + "|" + number.replaceAll("\\D", ""), new Contact(name, number));
    }

    List<Contact> sortedContacts() {
        List<Contact> list = new ArrayList<>(contacts.values());
        java.util.Collections.sort(list, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return list;
    }

    boolean isPhoneConnected() {
        return phoneState >= PHONE_CONNECTED && phoneState <= PHONE_TALK;
    }

    boolean isInCall() {
        return phoneState == PHONE_DIAL || phoneState == PHONE_RING || phoneState == PHONE_TALK;
    }

    /** Durée d'appel en secondes (compteur FYT si disponible, sinon horloge locale). */
    int callSeconds() {
        if (talkSeconds > 0) return talkSeconds;
        if (phoneState == PHONE_TALK && callStartedAt > 0) {
            return (int) ((SystemClock.elapsedRealtime() - callStartedAt) / 1000);
        }
        return 0;
    }

    // ---------------------------------------------------------------- commandes

    /** Compose un numéro : seuls les chiffres, +, * et # sont envoyés (pas d'espaces ni de parenthèses). */
    boolean dial(String number) {
        if (number == null) return false;
        String clean = number.replaceAll("[^0-9+*#]", "");
        return !clean.isEmpty() && cmd(btModule, C_DIAL, null, new String[]{clean});
    }

    boolean redial() { return cmd(btModule, C_REDIAL, null, null); }

    boolean answer() { return cmd(btModule, C_PICKUP, null, null); }

    boolean hangUp() { return cmd(btModule, C_HANG, null, null); }

    boolean reject() {
        boolean ok = cmd(btModule, C_REJECT_RING, null, null);
        return cmd(btModule, C_HANG, null, null) || ok;
    }

    /** Bascule le son de l'appel entre l'autoradio et le téléphone (micro + écouteur du téléphone). */
    boolean toggleAudio() {
        boolean ok = cmd(btModule, C_HFP, null, null);
        if (ok) {
            audioOnPhone = !audioOnPhone;
            dispatch(U_HFP);
        }
        return ok;
    }

    /** Touche du clavier pendant un appel (tonalités DTMF). key : '0'..'9', '*', '#'. */
    boolean sendKey(char key) {
        int code;
        if (key >= '0' && key <= '9') code = 2 + (key - '0');
        else if (key == '*') code = 13;
        else if (key == '#') code = 14;
        else return false;
        return cmd(btModule, C_KEY, new int[]{code}, null);
    }

    /**
     * Demande le répertoire. La liste actuelle est vidée seulement quand la première fiche arrive :
     * une synchronisation qui échoue ne fait pas perdre les contacts déjà reçus.
     */
    boolean downloadContacts() {
        boolean ok = cmd(btModule, C_DOWNLOAD_BOOK, null, null);
        if (ok) resetBookOnNextEntry = true;
        return ok;
    }

    boolean btavPlay() { return cmd(btModule, C_BTAV_PLAY, null, null); }

    boolean btavPause() { return cmd(btModule, C_BTAV_PAUSE, null, null); }

    boolean btavPlayPause() { return cmd(btModule, C_BTAV_PLAYPAUSE, null, null); }

    boolean btavNext() { return cmd(btModule, C_BTAV_NEXT, null, null); }

    boolean btavPrev() { return cmd(btModule, C_BTAV_PREV, null, null); }

    /** Choisit « musique Bluetooth » comme source audio de l'autoradio, puis lance la lecture. */
    boolean startBluetoothMusic() {
        boolean source = cmd(mainModule, 0, new int[]{3}, null);
        boolean play = btavPlay();
        return source || play;
    }

    // ---------------------------------------------------------------- écouteurs + journal

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void dispatch(int code) {
        for (Listener l : new ArrayList<>(listeners)) l.onFytUpdate(code);
    }

    private void note(String line) {
        // Une valeur reçue avec un retour à la ligne (titre, nom) échappait à l'anonymisation ligne par ligne.
        line = line.replace('\n', ' ').replace('\r', ' ');
        synchronized (log) {
            log.addLast(logTime.format(new Date()) + "  " + line);
            while (log.size() > 300) log.removeFirst();
        }
    }

    /** Journal des derniers échanges avec le service FYT (écran Diagnostic). */
    String dump() {
        return dump(false);
    }

    /**
     * @param anonymous true pour le rapport envoyé sur GitHub (dépôt public) : ni numéros, ni noms de contacts,
     *                  ni nom du téléphone. Seuls les codes, les états et la longueur des valeurs restent.
     */
    String dump(boolean anonymous) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service : ").append(status).append('\n')
                .append("Modules : BT=").append(btModule != null).append(" principal=").append(mainModule != null)
                .append(" son=").append(soundModule != null).append('\n')
                .append("Téléphone : état=").append(phoneState)
                .append(" nom=").append(anonymous ? mask(phoneName) : phoneName)
                .append(" numéro=").append(anonymous ? mask(callNumber) : callNumber).append('\n')
                .append("Audio d'appel sur le téléphone : ").append(audioOnPhone).append('\n')
                .append("Musique BT : ").append(anonymous ? mask(btavTitle) : btavTitle).append(" / ")
                .append(anonymous ? mask(btavArtist) : btavArtist)
                .append(btavPlaying ? " (lecture)" : " (pause)").append('\n')
                .append("Volume : ").append(volume).append('/').append(volumeMax).append(muted ? " (sourdine)" : "").append('\n')
                .append("Contacts reçus : ").append(contacts.size()).append('\n');
        sb.append("Tentatives de connexion :\n");
        for (String a : bindAttempts) sb.append("  ").append(a).append('\n');
        sb.append('\n');
        synchronized (log) {
            for (String l : log) sb.append(anonymous ? anonymize(l) : l).append('\n');
        }
        return sb.toString();
    }

    private static String mask(String s) {
        return s == null ? "null" : "<" + s.length() + " car.>";
    }

    /**
     * Lignes « ← uN <ints> <strs> » et « → cmd N <ints> <strs> » (ints/strs = « null » ou « [a, b] ») :
     * chaque texte de <strs> est remplacé par sa longueur (sauf les index « 0 »/« 1 »). Le reste est gardé.
     */
    private static String anonymize(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.*?(?:← u|→ cmd )\\d+ )(null|\\[[^\\]]*\\]) (\\[.*\\])$").matcher(line);
        if (!m.matches()) return line;
        String inner = m.group(3).substring(1, m.group(3).length() - 1);
        StringBuilder masked = new StringBuilder();
        for (String part : inner.split(", ", -1)) {
            if (masked.length() > 0) masked.append(", ");
            String t = part.trim();
            masked.append(t.equals("null") || t.isEmpty() || t.matches("\\d{1,2}") ? part : "<" + part.length() + " car.>");
        }
        return m.group(1) + m.group(2) + " [" + masked + "]";
    }
}
