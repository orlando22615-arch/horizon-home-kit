package com.horizon.home;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen TV guide for the Nuvio Live TV addon: a TiviMate-style grid (channels down the side,
 * time across), a live preview, full-screen playback with channel zapping and a see-through guide,
 * and a jump back to the channel's page in Nuvio. Opened from Nuvio's "TV GUIDE" link
 * (horizon://guide?cc=..&ch=..&src=..) or from the Live TV tile on the home screen.
 */
public class GuideActivity extends Activity {
    private static final String TAG = "HorizonGuide";
    static final String P_SRC = "guide_src", P_FAV = "guide_fav", P_LAST = "guide_last", P_CAT = "guide_cat", P_LANG = "guide_lang", P_CC = "guide_cc", P_SRC_F = "guide_srcf";
    static final Map<String, String> SOURCES = new LinkedHashMap<>();
    static {
        String[] l = {"samsung","Samsung TV Plus","pluto","Pluto TV","roku","Roku Channel","freetv","Free-TV",
                "validated","Validated list","online","Health-checked list","iptv","iptv-org"};
        for (int i = 0; i < l.length; i += 2) SOURCES.put(l[i], l[i + 1]);
    }
    /** Your Live TV addon link (set during setup); empty until then. */
    static final String DEFAULT_SRC = "";
    static final String RAW = "https://raw.githubusercontent.com/orlando22615-arch/nuvio-livetv/data/";

    static String src(Context c) { return U.prefs(c).getString(P_SRC, DEFAULT_SRC); }

    // ------------------------------------------------------------------ data

    static final class Prog {
        long s, e; String t, img, desc, sub, cat;
    }

    static final class Ch {
        String cc, id, name, logo; String[] cats = new String[0], langs = new String[0], srcs = new String[0];
        boolean works;
        JSONArray streams; boolean guide; int num;
        List<Prog> progs = new ArrayList<>();
        boolean full; // full schedule (with descriptions) loaded
        String key() { return cc + ":" + id; }
    }

    private final List<Ch> all = new ArrayList<>();
    private final List<Ch> rows = new ArrayList<>();
    private final Map<String, String> catNames = new HashMap<>();
    private SharedPreferences prefs;
    private Set<String> favs = new HashSet<>();
    private String cat = "news", lang = "hin", country = "in", srcF = "working";
    private final Map<String, String[]> countries = new LinkedHashMap<>(); // cc -> {flag, name, channels, guide}
    private int loadToken;
    static final Map<String, String> LANGS = new LinkedHashMap<>();
    static {
        String[] l = {"hin","Hindi","eng","English","ben","Bengali","tam","Tamil","tel","Telugu","mar","Marathi","kan","Kannada",
                "mal","Malayalam","guj","Gujarati","pan","Punjabi","urd","Urdu","ori","Odia","asm","Assamese","bho","Bhojpuri",
                "san","Sanskrit","nep","Nepali","spa","Spanish","fra","French","ara","Arabic","deu","German","por","Portuguese"};
        for (int i = 0; i < l.length; i += 2) LANGS.put(l[i], l[i + 1]);
    }

    // ------------------------------------------------------------------ views

    private static final int GRID = 0, PLAYER = 1, OVERLAY = 2;
    private int mode = GRID;
    private FrameLayout root, videoBox;
    private VideoView video;
    private LinearLayout panel, info, menu;
    private View previewHole;
    private Grid grid;
    private TextView iTitle, iMeta, iDesc, iNext, iChan, clock, status, chipCat, chipLang, chipCount, chipCc, chipSrc;
    private LinearLayout chipBar, middle;
    private android.widget.ScrollView dropScroll;
    private LinearLayout drop;
    private ImageView iArt, iLogo;
    private View iBar, iBarFill;
    private LinearLayout banner;
    private TextView bChan, bTitle, bTime, bNext;
    private ImageView bLogo;
    private View bFill;

    private Ch playing; private int streamIdx;
    private String pendingCh;
    private boolean softPending; // last-watched channel: focus it only if the filters already show it
    private final Runnable previewTask = this::previewFocused;
    private final Runnable infoTask = this::loadFullForFocus;
    private final Runnable hideBanner = () -> banner.animate().alpha(0f).setDuration(250).start();
    private final Runnable tick = new Runnable() { public void run() { clock.setText(fmt(System.currentTimeMillis() / 1000)); grid.invalidate(); updateInfo(); U.MAIN.postDelayed(this, 30000); } };

    private final SimpleDateFormat HM = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final SimpleDateFormat DAY = new SimpleDateFormat("EEE d MMM", Locale.getDefault());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        V.init(this);
        U.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = U.prefs(this);
        favs = new HashSet<>(prefs.getStringSet(P_FAV, new HashSet<>()));
        country = prefs.getString(P_CC, "in");
        srcF = prefs.getString(P_SRC_F, "working");
        loadFilters();
        build();
        handle(getIntent());
        load();
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        handle(i);
        if (!all.isEmpty()) { applyGroup(); focusPending(); }
    }

    private void handle(Intent i) {
        Uri d = i != null ? i.getData() : null;
        if (d == null) return;
        String s = d.getQueryParameter("src");
        if (!TextUtils.isEmpty(s) && s.startsWith("https://") && !s.equals(src(this))) {
            prefs.edit().putString(P_SRC, s).apply();
            all.clear();
        }
        String cc = d.getQueryParameter("cc"), ch = d.getQueryParameter("ch");
        if (cc != null && ch != null) {
            pendingCh = cc + ":" + ch; softPending = false;
            if (!cc.equals(country)) { country = cc; prefs.edit().putString(P_CC, country).apply(); loadFilters(); if (loadToken > 0) { all.clear(); load(); } }
        }
        setMode(GRID);
    }

    @Override protected void onResume() { super.onResume(); U.MAIN.post(tick); }
    @Override protected void onPause() { super.onPause(); U.MAIN.removeCallbacks(tick); }

    @Override
    protected void onStop() {
        super.onStop();
        stopVideo();
        if (playing != null) prefs.edit().putString(P_LAST, playing.key()).apply();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        if (mode != GRID) setMode(GRID);
        schedulePreview(600);
    }

    // ------------------------------------------------------------------ layout

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF07080B);
        setContentView(root);

        videoBox = new FrameLayout(this);
        videoBox.setBackgroundColor(0xFF000000);
        video = new VideoView(this);
        videoBox.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        status = V.text(this, "", 13, 600, 0xCCFFFFFF);
        status.setGravity(Gravity.CENTER);
        videoBox.addView(status, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        root.addView(videoBox, new FrameLayout.LayoutParams(1, 1));
        video.setOnPreparedListener(mp -> { status.setText(""); mp.setLooping(false); });
        video.setOnErrorListener((mp, what, extra) -> { nextStream(); return true; });
        video.setOnCompletionListener(mp -> { if (playing != null) play(playing, streamIdx); });
        video.setOnInfoListener((mp, what, extra) -> {
            if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status.setText("Buffering…");
            else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) status.setText("");
            return false;
        });

        // guide panel: info on top, grid below
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        int pad = V.edge(this);
        int px = V.dp(pad * 0.75f);
        root.setFocusable(true);

        // top bar: filters as dropdown chips
        chipBar = new LinearLayout(this);
        chipBar.setGravity(Gravity.CENTER_VERTICAL);
        chipBar.setPadding(px, V.dp(18), px, V.dp(12));
        chipBar.addView(V.caps(this, "● Live TV", 10, 0xFFFF5A5A));
        chipCc = chip();
        chipBar.addView(chipCc, lp(-2, -2, 16, 0, 0, 0));
        chipCat = chip();
        chipBar.addView(chipCat, lp(-2, -2, 8, 0, 0, 0));
        chipLang = chip();
        chipBar.addView(chipLang, lp(-2, -2, 8, 0, 0, 0));
        chipSrc = chip();
        chipBar.addView(chipSrc, lp(-2, -2, 8, 0, 0, 0));
        chipCount = V.text(this, "", 11, 600, 0x80FFFFFF);
        chipBar.addView(chipCount, lp(-2, -2, 14, 0, 0, 0));
        chipBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        clock = V.text(this, "", 14, 700, V.TEXT);
        chipBar.addView(clock);
        panel.addView(chipBar, new LinearLayout.LayoutParams(-1, -2));

        // info + big live preview
        middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.HORIZONTAL);
        middle.setPadding(px, 0, px, V.dp(4));
        panel.addView(middle, new LinearLayout.LayoutParams(-1, -2));

        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        middle.addView(info, new LinearLayout.LayoutParams(0, -1, 1f));
        LinearLayout chRow = new LinearLayout(this);
        chRow.setGravity(Gravity.CENTER_VERTICAL);
        iLogo = V.image(this, ImageView.ScaleType.FIT_CENTER);
        chRow.addView(iLogo, new LinearLayout.LayoutParams(V.dp(40), V.dp(24)));
        iChan = V.text(this, "", 11.5f, 700, 0xB3FFFFFF);
        chRow.addView(iChan, lp(-2, -2, 8, 0, 0, 0));
        info.addView(chRow, lp(-1, -2, 0, 6, 0, 0));
        iTitle = V.text(this, "Loading channels…", 22, 800, V.TEXT);
        iTitle.setMaxLines(2);
        iTitle.setEllipsize(TextUtils.TruncateAt.END);
        iTitle.setLineSpacing(V.dpf(2), 1f);
        info.addView(iTitle, lp(-1, -2, 0, 12, 0, 0));
        iMeta = V.text(this, "", 11.5f, 600, 0xB3FFFFFF);
        iMeta.setLineSpacing(V.dpf(3), 1f);
        info.addView(iMeta, lp(-1, -2, 0, 9, 0, 0));
        FrameLayout bar = new FrameLayout(this);
        iBar = bar;
        bar.setBackground(V.shape(0x26FFFFFF, 2, 0));
        iBarFill = new View(this);
        iBarFill.setBackground(V.shape(V.accent(), 2, 0));
        bar.addView(iBarFill, new FrameLayout.LayoutParams(0, -1));
        info.addView(bar, lp(V.dp(180), V.dp(3), 0, 10, 0, 0));
        iDesc = V.text(this, "", 11.5f, 500, 0xA6FFFFFF);
        iDesc.setLineSpacing(V.dpf(3), 1f);
        iDesc.setMaxLines(6);
        iDesc.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(iDesc, lp(-1, -2, 0, 12, 0, 0));
        iNext = V.text(this, "", 11, 600, 0x80FFFFFF);
        iNext.setMaxLines(2);
        iNext.setEllipsize(TextUtils.TruncateAt.END);
        info.addView(iNext, lp(-1, -2, 0, 10, 0, 0));

        previewHole = new View(this);
        middle.addView(previewHole, lp(V.dp(528), V.dp(297), 20, 0, 0, 0));

        grid = new Grid(this);
        panel.addView(grid, new LinearLayout.LayoutParams(-1, 0, 1f));

        // player banner
        banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(V.dp(pad * 0.75f), V.dp(20), V.dp(pad * 0.75f), V.dp(26));
        banner.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xF0000000, 0xB0000000, 0x00000000}));
        bLogo = V.image(this, ImageView.ScaleType.FIT_CENTER);
        banner.addView(bLogo, new LinearLayout.LayoutParams(V.dp(70), V.dp(44)));
        LinearLayout bt = new LinearLayout(this);
        bt.setOrientation(LinearLayout.VERTICAL);
        banner.addView(bt, lp(0, -2, 16, 0, 0, 0));
        ((LinearLayout.LayoutParams) bt.getLayoutParams()).weight = 1;
        bChan = V.text(this, "", 11.5f, 700, 0xB3FFFFFF);
        bt.addView(bChan);
        bTitle = V.text(this, "", 20, 800, V.TEXT);
        bTitle.setSingleLine(true);
        bTitle.setEllipsize(TextUtils.TruncateAt.END);
        bt.addView(bTitle, lp(-1, -2, 0, 5, 0, 0));
        FrameLayout bb = new FrameLayout(this);
        bb.setBackground(V.shape(0x33FFFFFF, 2, 0));
        bFill = new View(this);
        bFill.setBackground(V.shape(V.accent(), 2, 0));
        bb.addView(bFill, new FrameLayout.LayoutParams(0, -1));
        bt.addView(bb, lp(V.dp(260), V.dp(3), 0, 8, 0, 0));
        bNext = V.text(this, "", 11, 600, 0x99FFFFFF);
        bNext.setSingleLine(true);
        bNext.setEllipsize(TextUtils.TruncateAt.END);
        bt.addView(bNext, lp(-1, -2, 0, 7, 0, 0));
        bTime = V.text(this, "", 12, 700, 0xCCFFFFFF);
        banner.addView(bTime, lp(-2, -2, 16, 0, 0, 0));
        banner.setAlpha(0f);
        root.addView(banner, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        // dropdown for the filter chips
        drop = new LinearLayout(this);
        drop.setOrientation(LinearLayout.VERTICAL);
        drop.setPadding(V.dp(8), V.dp(8), V.dp(8), V.dp(8));
        dropScroll = new android.widget.ScrollView(this);
        dropScroll.setVerticalScrollBarEnabled(false);
        dropScroll.setBackground(V.shape(0xF71A1C22, 14, 0));
        dropScroll.setElevation(V.dpf(12));
        dropScroll.addView(drop);
        dropScroll.setVisibility(View.GONE);
        root.addView(dropScroll, new FrameLayout.LayoutParams(V.dp(250), -2));

        // context menu
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(V.dp(10), V.dp(10), V.dp(10), V.dp(10));
        menu.setBackground(V.shape(0xF2181A20, 16, 0));
        menu.setVisibility(View.GONE);
        root.addView(menu, new FrameLayout.LayoutParams(V.dp(300), -2, Gravity.CENTER));

        setMode(GRID);
        root.requestFocus();
    }

    private LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(V.dp(l), V.dp(t), V.dp(r), V.dp(b));
        return p;
    }

    private void setMode(int m) {
        mode = m;
        boolean g = m == GRID;
        if (chipBar != null) { chipBar.setVisibility(g ? View.VISIBLE : View.GONE); middle.setVisibility(g ? View.VISIBLE : View.GONE); }
        if (m == GRID) {
            panel.setVisibility(View.VISIBLE);
            panel.setBackgroundColor(0x00000000);
            root.setBackgroundColor(0xFF07080B);
            info.setVisibility(View.VISIBLE);
            banner.setAlpha(0f);
            panel.post(this::placePreview);
        } else if (m == PLAYER) {
            panel.setVisibility(View.GONE);
            videoBox.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            showBanner();
        } else {
            panel.setVisibility(View.VISIBLE);
            panel.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] {0xF507080B, 0xE607080B, 0x8007080B}));
            videoBox.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
            banner.setAlpha(0f);
            if (playing != null) grid.focus(playing);
        }
        grid.invalidate();
    }

    private void placePreview() {
        if (mode != GRID) return;
        int[] at = new int[2];
        previewHole.getLocationInWindow(at);
        int[] r = new int[2];
        root.getLocationInWindow(r);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(previewHole.getWidth(), previewHole.getHeight());
        p.leftMargin = at[0] - r[0];
        p.topMargin = at[1] - r[1];
        videoBox.setLayoutParams(p);
        videoBox.setClipToOutline(true);
        videoBox.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View v, android.graphics.Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), V.dpf(10)); }
        });
    }

    // ------------------------------------------------------------------ loading

    private File cacheFile(String name) { return new File(getCacheDir(), "guide_" + name + ".json"); }

    private void loadFilters() {
        String defLang = "in".equals(country) ? "hin" : ("us".equals(country) || "uk".equals(country) || "ca".equals(country) || "au".equals(country) ? "eng" : "all");
        cat = prefs.getString(P_CAT + "_" + country, "news");
        lang = prefs.getString(P_LANG + "_" + country, defLang);
    }

    private void load() {
        final String cc = country;
        final int token = ++loadToken;
        U.IO.execute(() -> {
            File fc = cacheFile("c_" + cc), fn = cacheFile("now_" + cc);
            try {
                if (fc.exists()) {
                    final String c1 = new String(Files.readAllBytes(fc.toPath()), StandardCharsets.UTF_8);
                    final String n1 = fn.exists() ? new String(Files.readAllBytes(fn.toPath()), StandardCharsets.UTF_8) : null;
                    U.MAIN.post(() -> { if (token == loadToken) apply(cc, c1, n1); });
                }
            } catch (Exception ignored) {}
            try {
                final String c2 = Http.get(RAW + "c/" + cc + ".json");
                final String n2 = Http.get(RAW + "now/" + cc + ".json");
                write(fc, c2); write(fn, n2);
                U.MAIN.post(() -> { if (token == loadToken) apply(cc, c2, n2); });
            } catch (Exception e) {
                Log.w(TAG, "load failed", e);
                U.MAIN.post(() -> { if (token == loadToken && all.isEmpty()) iTitle.setText("Couldn't load channels. Check the internet connection."); });
            }
            if (countries.isEmpty()) {
                try {
                    JSONObject o = new JSONObject(Http.get(RAW + "countries.json"));
                    final Map<String, String[]> m = new LinkedHashMap<>();
                    List<String> keys = new ArrayList<>();
                    for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) keys.add(it.next());
                    Collections.sort(keys, (x, y) -> o.optJSONObject(y).optInt("channels") - o.optJSONObject(x).optInt("channels"));
                    for (String k : keys) {
                        JSONObject c = o.optJSONObject(k);
                        m.put(k, new String[] {c.optString("flag"), c.optString("name"), String.valueOf(c.optInt("channels")), String.valueOf(c.optInt("guide"))});
                    }
                    U.MAIN.post(() -> { countries.putAll(m); updateChips(); });
                } catch (Exception ignored) {}
            }
            if (catNames.isEmpty()) {
                try {
                    JSONObject cats = new JSONObject(Http.get(RAW + "categories.json"));
                    final Map<String, String> m = new HashMap<>();
                    for (java.util.Iterator<String> it = cats.keys(); it.hasNext(); ) { String k = it.next(); m.put(k, cats.optString(k)); }
                    U.MAIN.post(() -> { catNames.putAll(m); updateChips(); });
                } catch (Exception ignored) {}
            }
        });
    }

    private static void write(File f, String s) {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    private void apply(String cc, String chJson, String nowJson) {
        try {
            if (!cc.equals(country)) return;
            Ch keepFocus = grid.sel();
            Map<String, Ch> old = new HashMap<>();
            for (Ch c : all) old.put(c.key(), c);
            JSONArray a = new JSONArray(chJson);
            JSONObject now = nowJson != null ? new JSONObject(nowJson).optJSONObject("p") : null;
            if (now == null) now = new JSONObject();
            List<Ch> list = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Ch c = new Ch();
                if (o.optInt("x") == 1) continue; // adult channels stay out
                c.cc = cc; c.id = o.optString("id"); c.name = clean(o.optString("n"));
                c.logo = o.optString("l", null);
                JSONArray cs = o.optJSONArray("c");
                if (cs != null) { c.cats = new String[cs.length()]; for (int k = 0; k < cs.length(); k++) c.cats[k] = cs.optString(k); }
                JSONArray la = o.optJSONArray("la");
                if (la != null) { c.langs = new String[la.length()]; for (int k = 0; k < la.length(); k++) c.langs[k] = la.optString(k); }
                c.streams = o.optJSONArray("s");
                JSONArray so = o.optJSONArray("o");
                if (so != null) { c.srcs = new String[so.length()]; for (int k = 0; k < so.length(); k++) c.srcs[k] = so.optString(k); }
                else c.srcs = new String[] {"iptv"};
                c.works = o.optInt("v") == 1;
                c.guide = o.optInt("g") == 1;
                Ch prev = old.get(c.key());
                if (prev != null && prev.full) { c.progs = prev.progs; c.full = true; }
                else c.progs = parseProgs(now.optJSONArray(c.id));
                list.add(c);
            }
            // guide channels first, then the rest; alphabetical inside each
            Collections.sort(list, (x, y) -> x.works != y.works ? (x.works ? -1 : 1)
                    : x.guide != y.guide ? (x.guide ? -1 : 1) : x.name.compareToIgnoreCase(y.name));
            for (int i = 0; i < list.size(); i++) list.get(i).num = i + 1;
            all.clear();
            all.addAll(list);
            if (pendingCh == null && keepFocus != null) pendingCh = keepFocus.key();
            if (pendingCh == null) { pendingCh = prefs.getString(P_LAST, null); softPending = true; }
            // start from the saved choices (an earlier empty view may have widened them)
            srcF = prefs.getString(P_SRC_F, "working");
            loadFilters();
            applyGroup();
            focusPending();
        } catch (Exception e) {
            Log.w(TAG, "apply", e);
        }
    }

    private static String clean(String n) {
        return n.replaceAll("\\s*(\\((\\d{3,4}p|geo-?blocked|not 24/7)[^)]*\\)|\\[(geo-?blocked|not 24/7)[^\\]]*\\])", "").trim();
    }

    private static List<Prog> parseProgs(JSONArray a) {
        List<Prog> l = new ArrayList<>();
        if (a == null) return l;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Prog p = new Prog();
            p.s = o.optLong("s"); p.e = o.optLong("e"); p.t = o.optString("t");
            p.img = o.optString("i", null); p.desc = o.optString("d", null); p.sub = o.optString("st", null);
            JSONArray c = o.optJSONArray("c");
            if (c != null && c.length() > 0) p.cat = c.optString(0);
            l.add(p);
        }
        return l;
    }

    private void focusPending() {
        if (pendingCh != null) {
            for (Ch c : rows) if (c.key().equals(pendingCh)) { grid.focus(c); break; }
            if (grid.sel() == null || !grid.sel().key().equals(pendingCh)) {
                // not in the current group: switch to all channels
                if (!softPending) for (Ch c : all) if (c.key().equals(pendingCh)) { cat = "all"; lang = "all"; srcF = "all"; applyGroup(); grid.focus(c); break; }
            }
            pendingCh = null;
            softPending = false;
        }
        onFocusChanged();
    }

    // ------------------------------------------------------------------ filters

    private TextView chip() {
        final TextView t = V.text(this, "", 12, 700, V.TEXT);
        t.setPadding(V.dp(14), V.dp(8), V.dp(14), V.dp(8));
        t.setFocusable(true);
        t.setBackground(V.shape(0x1FFFFFFF, 18, 0));
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(V.shape(has ? V.TEXT : 0x1FFFFFFF, 18, 0));
            t.setTextColor(has ? V.INK : V.TEXT);
        });
        t.setOnClickListener(v -> openDrop(t));
        return t;
    }

    private boolean matchCat(Ch c, String k) {
        if ("all".equals(k)) return true;
        if ("fav".equals(k)) return favs.contains(c.key());
        for (String x : c.cats) if (x.equals(k)) return true;
        return false;
    }

    private boolean matchSrc(Ch c, String k) {
        if ("all".equals(k)) return true;
        if ("working".equals(k)) return c.works;
        for (String x : c.srcs) if (x.equals(k)) return true;
        return false;
    }

    private boolean matchLang(Ch c, String k) {
        if ("all".equals(k)) return true;
        for (String x : c.langs) if (x.equals(k)) return true;
        return false;
    }

    private void applyGroup() {
        rows.clear();
        for (Ch c : all) if (matchCat(c, cat) && matchLang(c, lang) && matchSrc(c, srcF)) rows.add(c);
        if (rows.isEmpty() && !all.isEmpty() && !"fav".equals(cat)) {
            if (!"all".equals(srcF)) { srcF = "all"; applyGroup(); return; }
            // nothing for this country with these filters: widen, without changing what's saved
            if (!"all".equals(lang)) { lang = "all"; applyGroup(); return; }
            if (!"all".equals(cat)) { cat = "all"; applyGroup(); return; }
        }
        updateChips();
        int g = 0;
        for (Ch c : rows) if (c.guide) g++;
        chipCount.setText(rows.size() + " channels  ·  " + g + " with guide");
        grid.setRows(rows);
        if (rows.isEmpty() && !all.isEmpty()) {
            iTitle.setText("No channels for this choice");
            iMeta.setText("Pick another category or language at the top");
            iDesc.setText(""); iNext.setText(""); iChan.setText(""); iLogo.setImageDrawable(null);
            iBar.setVisibility(View.INVISIBLE);
        }
    }

    private void updateChips() {
        String[] c = countries.get(country);
        chipCc.setText((c != null ? c[0] + "  " + c[1] : country.toUpperCase(Locale.ROOT)) + "  ▾");
        chipCat.setText(catName(cat) + "  ▾");
        chipLang.setText(langName(lang) + "  ▾");
        chipSrc.setText(srcName(srcF) + "  ▾");
    }

    private String srcName(String k) {
        if ("all".equals(k)) return "All sources";
        if ("working".equals(k)) return "✓ Working";
        String n = SOURCES.get(k);
        return n != null ? n : k;
    }

    private String catName(String k) {
        if ("all".equals(k)) return "All categories";
        if ("fav".equals(k)) return "★ Favourites";
        String n = catNames.get(k);
        return n != null ? n : Character.toUpperCase(k.charAt(0)) + k.substring(1);
    }

    private String langName(String k) {
        if ("all".equals(k)) return "All languages";
        String n = LANGS.get(k);
        return n != null ? n : k.toUpperCase(Locale.ROOT);
    }

    private TextView openChip;

    private void openDrop(final TextView chip) {
        if (chip == chipCc) { openCountries(); return; }
        if (chip == chipSrc) { openSources(); return; }
        final boolean isCat = chip == chipCat;
        drop.removeAllViews();
        // counts follow the other filter, so the numbers match what you'll see
        LinkedHashMap<String, Integer> opts = new LinkedHashMap<>();
        Map<String, Integer> cnt = new HashMap<>();
        int total = 0, fav = 0;
        for (Ch c : all) {
            if (!matchSrc(c, srcF) || (isCat ? !matchLang(c, lang) : !matchCat(c, cat))) continue;
            total++;
            if (favs.contains(c.key())) fav++;
            for (String k : isCat ? c.cats : c.langs) cnt.put(k, cnt.containsKey(k) ? cnt.get(k) + 1 : 1);
        }
        if (isCat) { opts.put("fav", fav); opts.put("all", total); }
        else opts.put("all", total);
        List<Map.Entry<String, Integer>> l = new ArrayList<>(cnt.entrySet());
        Collections.sort(l, (a, b) -> b.getValue() - a.getValue());
        for (Map.Entry<String, Integer> e : l) opts.put(e.getKey(), e.getValue());
        final String cur = isCat ? cat : lang;
        View focusMe = null;
        for (final Map.Entry<String, Integer> e : opts.entrySet()) {
            final LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(V.dp(12), V.dp(9), V.dp(12), V.dp(9));
            r.setFocusable(true);
            boolean on = e.getKey().equals(cur);
            final TextView n = V.text(this, (on ? "✓  " : "") + (isCat ? catName(e.getKey()) : langName(e.getKey())), 13, 700, on ? V.accent() : V.TEXT);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            final TextView c = V.text(this, String.valueOf(e.getValue()), 11, 600, 0x80FFFFFF);
            r.addView(c);
            r.setOnFocusChangeListener((v, has) -> v.setBackground(has ? V.shape(0x29FFFFFF, 10, 0) : null));
            r.setOnClickListener(v -> {
                if (isCat) { cat = e.getKey(); prefs.edit().putString(P_CAT + "_" + country, cat).apply(); }
                else { lang = e.getKey(); prefs.edit().putString(P_LANG + "_" + country, lang).apply(); }
                Ch keep = grid.sel();
                applyGroup();
                if (keep != null) grid.focus(keep);
                closeDrop();
                onFocusChanged();
            });
            drop.addView(r, new LinearLayout.LayoutParams(-1, -2));
            if (on) focusMe = r;
        }
        showDrop(chip, opts.size(), focusMe);
    }

    private void showDrop(TextView chip, int n, View focusMe) {
        int[] at = new int[2], rt = new int[2];
        chip.getLocationInWindow(at);
        root.getLocationInWindow(rt);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(V.dp(270), Math.min(V.dp(36) * n + V.dp(16), V.dp(400)));
        p.leftMargin = at[0] - rt[0];
        p.topMargin = at[1] - rt[1] + chip.getHeight() + V.dp(6);
        dropScroll.setLayoutParams(p);
        dropScroll.setVisibility(View.VISIBLE);
        dropScroll.bringToFront();
        dropScroll.scrollTo(0, 0);
        openChip = chip;
        (focusMe != null ? focusMe : drop.getChildAt(0)).requestFocus();
    }

    private void openSources() {
        drop.removeAllViews();
        LinkedHashMap<String, Integer> opts = new LinkedHashMap<>();
        int total = 0, ok = 0;
        Map<String, Integer> cnt = new HashMap<>();
        for (Ch c : all) {
            if (!matchCat(c, cat) || !matchLang(c, lang)) continue;
            total++;
            if (c.works) ok++;
            for (String x : c.srcs) cnt.put(x, cnt.containsKey(x) ? cnt.get(x) + 1 : 1);
        }
        opts.put("working", ok);
        opts.put("all", total);
        for (String k : SOURCES.keySet()) if (cnt.containsKey(k)) opts.put(k, cnt.get(k));
        View focusMe = null;
        for (final Map.Entry<String, Integer> e : opts.entrySet()) {
            final LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(V.dp(12), V.dp(9), V.dp(12), V.dp(9));
            r.setFocusable(true);
            boolean on = e.getKey().equals(srcF);
            r.addView(V.text(this, (on ? "✓  " : "") + srcName(e.getKey()).replace("✓ ", ""), 13, 700, on ? V.accent() : V.TEXT), new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(V.text(this, String.valueOf(e.getValue()), 11, 600, 0x80FFFFFF));
            r.setOnFocusChangeListener((v, has) -> v.setBackground(has ? V.shape(0x29FFFFFF, 10, 0) : null));
            r.setOnClickListener(v -> {
                srcF = e.getKey();
                prefs.edit().putString(P_SRC_F, srcF).apply();
                Ch keep = grid.sel();
                applyGroup();
                if (keep != null) grid.focus(keep);
                closeDrop();
                onFocusChanged();
            });
            drop.addView(r, new LinearLayout.LayoutParams(-1, -2));
            if (on) focusMe = r;
        }
        showDrop(chipSrc, opts.size(), focusMe);
    }

    private void openCountries() {
        drop.removeAllViews();
        View focusMe = null;
        for (final Map.Entry<String, String[]> e : countries.entrySet()) {
            final String[] c = e.getValue();
            final LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(V.dp(12), V.dp(9), V.dp(12), V.dp(9));
            r.setFocusable(true);
            boolean on = e.getKey().equals(country);
            TextView n = V.text(this, (on ? "✓  " : "") + c[0] + "  " + c[1], 13, 700, on ? V.accent() : V.TEXT);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(n, new LinearLayout.LayoutParams(0, -2, 1f));
            r.addView(V.text(this, c[2] + (Integer.parseInt(c[3]) > 0 ? "  ·  " + c[3] + " guide" : ""), 10.5f, 600, 0x80FFFFFF));
            r.setOnFocusChangeListener((v, has) -> v.setBackground(has ? V.shape(0x29FFFFFF, 10, 0) : null));
            r.setOnClickListener(v -> {
                closeDrop();
                if (e.getKey().equals(country)) return;
                country = e.getKey();
                prefs.edit().putString(P_CC, country).apply();
                loadFilters();
                all.clear(); rows.clear(); grid.setRows(rows);
                iTitle.setText("Loading " + c[1] + "…"); iMeta.setText(""); iDesc.setText(""); iNext.setText(""); iChan.setText("");
                updateChips();
                load();
            });
            drop.addView(r, new LinearLayout.LayoutParams(-1, -2));
            if (on) focusMe = r;
        }
        if (countries.isEmpty()) return;
        showDrop(chipCc, countries.size(), focusMe);
    }

    private void closeDrop() {
        dropScroll.setVisibility(View.GONE);
        if (openChip != null) openChip.requestFocus();
    }

    private boolean dropOpen() { return dropScroll.getVisibility() == View.VISIBLE; }

    private void focusChips() {
        if (mode != GRID) setMode(GRID);
        chipCc.requestFocus();
    }

    private void focusGrid() { root.requestFocus(); }

    // ------------------------------------------------------------------ menu

    private void openMenu(final Ch c) {
        if (c == null) return;
        menu.removeAllViews();
        menu.addView(V.text(this, c.name, 14, 800, V.TEXT), lp(-1, -2, 10, 6, 10, 10));
        final boolean fav = favs.contains(c.key());
        menuItem(fav ? "★  Remove from favourites" : "☆  Add to favourites", () -> {
            if (fav) favs.remove(c.key()); else favs.add(c.key());
            prefs.edit().putStringSet(P_FAV, new HashSet<>(favs)).apply();
            if ("fav".equals(cat)) applyGroup();
            grid.invalidate();
        });
        menuItem("▶  Watch full screen", () -> watch(c));
        menuItem("◆  Open in Nuvio", () -> openInNuvio(c));
        menuItem("☰  Category", () -> { focusChips(); openDrop(chipCat); });
        menuItem("文  Language", () -> { focusChips(); chipLang.requestFocus(); openDrop(chipLang); });
        if (c.streams != null && c.streams.length() > 1)
            menuItem("⇄  Try another link (" + c.streams.length() + ")", () -> { if (playing == c) nextStream(); else play(c, 1); });
        menu.setVisibility(View.VISIBLE);
        menu.getChildAt(1).requestFocus();
    }

    private void menuItem(String label, final Runnable r) {
        TextView t = V.text(this, label, 13, 700, V.TEXT);
        t.setPadding(V.dp(12), V.dp(10), V.dp(12), V.dp(10));
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, has) -> v.setBackground(has ? V.shape(0x26FFFFFF, 10, 0) : null));
        t.setOnClickListener(v -> { closeMenu(); focusGrid(); r.run(); });
        menu.addView(t, new LinearLayout.LayoutParams(-1, -2));
    }

    private void closeMenu() { menu.setVisibility(View.GONE); }
    private boolean menuOpen() { return menu.getVisibility() == View.VISIBLE; }

    private void openInNuvio(Ch c) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("nuvio://meta?type=tv&id=" + Uri.encode("nltv:" + c.cc + ":" + c.id, ":.")));
            i.setPackage(U.NUVIO);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            stopVideo();
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Nuvio isn't installed", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ playback

    private void watch(Ch c) {
        if (playing != c || !video.isPlaying()) play(c, 0);
        setMode(PLAYER);
    }

    private int playToken;
    private String playingQuality;

    private void play(Ch c, int idx) {
        if (c.streams == null || idx >= c.streams.length()) {
            status.setText(c.streams == null || c.streams.length() == 0 ? "No link for this channel" : "This channel isn't working right now");
            video.stopPlayback();
            return;
        }
        playing = c; streamIdx = idx;
        JSONObject s = c.streams.optJSONObject(idx);
        if (s == null) { nextStream(); return; }
        final Map<String, String> h = new HashMap<>();
        if (s.has("a")) h.put("User-Agent", s.optString("a"));
        if (s.has("r")) h.put("Referer", s.optString("r"));
        final String url = s.optString("u");
        final int token = ++playToken;
        status.setText("Tuning " + c.name + "…");
        try { video.stopPlayback(); } catch (Exception ignored) {}
        // Android's built-in player starts HLS on the lowest quality and rarely steps up,
        // so pick the sharpest variant (up to 1080p) ourselves and play that directly.
        U.IO.execute(() -> {
            String[] best = bestVariant(url, h);
            U.MAIN.post(() -> {
                if (token != playToken || isFinishing()) return;
                playingQuality = best[1];
                try {
                    video.setVideoURI(Uri.parse(best[0]), h);
                    video.start();
                } catch (Exception e) { nextStream(); }
                if (mode == PLAYER) showBanner();
            });
        });
        if (mode == PLAYER) showBanner();
    }

    /** {url to play, quality label}; falls back to the original link. */
    private static String[] bestVariant(String url, Map<String, String> headers) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            for (Map.Entry<String, String> e : headers.entrySet()) conn.setRequestProperty(e.getKey(), e.getValue());
            if (!headers.containsKey("User-Agent")) conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36");
            int code = conn.getResponseCode();
            String finalUrl = conn.getURL().toString();
            if (code >= 400) return new String[] {url, null};
            java.io.InputStream in = conn.getInputStream();
            byte[] buf = new byte[131072];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            if (!text.startsWith("#EXTM3U") || !text.contains("#EXT-X-STREAM-INF")) return new String[] {finalUrl, null};
            // separate audio tracks only work through the master playlist
            if (text.contains("TYPE=AUDIO") && text.contains("URI=")) return new String[] {finalUrl, null};
            String[] lines = text.split("\r?\n");
            String bestUri = null; int bestH = -1; long bestBw = -1;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i];
                if (!l.startsWith("#EXT-X-STREAM-INF")) continue;
                int hgt = 0; long bw = 0;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("RESOLUTION=(\\d+)x(\\d+)").matcher(l);
                if (m.find()) hgt = Integer.parseInt(m.group(2));
                java.util.regex.Matcher mb = java.util.regex.Pattern.compile("[^-]BANDWIDTH=(\\d+)").matcher(l);
                if (mb.find()) bw = Long.parseLong(mb.group(1));
                String uri = null;
                for (int j = i + 1; j < lines.length; j++) {
                    String u = lines[j].trim();
                    if (u.isEmpty() || u.startsWith("#")) continue;
                    uri = u; break;
                }
                if (uri == null) continue;
                int capH = hgt > 1088 ? 0 : hgt; // the projector is 1080p: skip 4K variants
                if (capH > bestH || (capH == bestH && bw > bestBw)) { bestH = capH; bestBw = bw; bestUri = uri; }
            }
            if (bestUri == null) return new String[] {finalUrl, null};
            String abs = new java.net.URL(new java.net.URL(finalUrl), bestUri).toString();
            return new String[] {abs, bestH > 0 ? bestH + "p" : null};
        } catch (Exception e) {
            return new String[] {url, null};
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void nextStream() {
        if (playing == null) return;
        play(playing, streamIdx + 1);
    }

    private void stopVideo() {
        U.MAIN.removeCallbacks(previewTask);
        try { video.stopPlayback(); } catch (Exception ignored) {}
    }

    private void zap(int dir) {
        if (rows.isEmpty()) return;
        int i = rows.indexOf(playing);
        i = ((i < 0 ? 0 : i + dir) % rows.size() + rows.size()) % rows.size();
        Ch c = rows.get(i);
        grid.focus(c);
        play(c, 0);
        showBanner();
    }

    private void showBanner() {
        Ch c = playing;
        if (c == null) return;
        long t = System.currentTimeMillis() / 1000;
        Prog[] cn = curNext(c, t);
        bChan.setText(c.num + "   " + c.name);
        bTitle.setText(cn[0] != null ? cn[0].t : c.name);
        bTime.setText((playingQuality != null ? playingQuality + "   ·   " : "") + (cn[0] != null ? fmt(cn[0].s) + " – " + fmt(cn[0].e) : fmt(t)));
        bNext.setText(cn[1] != null ? "Next  " + fmt(cn[1].s) + "  " + cn[1].t : "");
        setFill(bFill, V.dp(260), cn[0]);
        bLogo.setImageDrawable(null);
        if (c.logo != null) Img.load(this, c.logo, V.dp(140), b -> { if (b != null && playing == c) bLogo.setImageBitmap(b); });
        banner.animate().cancel();
        banner.setAlpha(1f);
        U.MAIN.removeCallbacks(hideBanner);
        U.MAIN.postDelayed(hideBanner, 5000);
    }

    private void schedulePreview(long ms) {
        U.MAIN.removeCallbacks(previewTask);
        U.MAIN.postDelayed(previewTask, ms);
    }

    private void previewFocused() {
        if (mode != GRID || isFinishing()) return;
        Ch c = grid.sel();
        if (c != null && (c != playing || !video.isPlaying())) play(c, 0);
    }

    // ------------------------------------------------------------------ info panel

    private void onFocusChanged() {
        updateInfo();
        U.MAIN.removeCallbacks(infoTask);
        U.MAIN.postDelayed(infoTask, 350);
        if (mode == GRID) schedulePreview(1500);
    }

    private void loadFullForFocus() {
        final Ch c = grid.sel();
        if (c == null || c.full || !c.guide) return;
        final String url = RAW + "g/" + c.cc + "/" + enc(c.id) + ".json";
        U.IO.execute(() -> {
            try {
                final List<Prog> l = parseProgs(new JSONArray(Http.get(url)));
                U.MAIN.post(() -> { if (!l.isEmpty()) { c.progs = l; } c.full = true; grid.invalidate(); if (grid.sel() == c) updateInfo(); });
            } catch (Exception e) { U.MAIN.post(() -> c.full = true); }
        });
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; }
    }

    private String lastArt;

    private void updateInfo() {
        Ch c = grid.sel();
        if (c == null) return;
        Prog p = grid.selProg();
        long t = System.currentTimeMillis() / 1000;
        iChan.setText(c.num + "  ·  " + c.name + (favs.contains(c.key()) ? "  ★" : ""));
        if (p == null) {
            iTitle.setText(c.name);
            iMeta.setText(c.guide ? "No listing for this time" : "No TV guide for this channel");
            iDesc.setText("");
            iNext.setText("");
            iBar.setVisibility(View.INVISIBLE);
        } else {
            iTitle.setText(p.t);
            String when = p.s <= t && p.e > t ? "On now  ·  " + Math.max(1, (p.e - t) / 60) + " min left"
                    : (p.s > t ? (sameDay(p.s, t) ? "Today" : DAY.format(p.s * 1000)) + "  ·  " + fmt(p.s) : "Ended");
            iMeta.setText(when + "  ·  " + fmt(p.s) + " – " + fmt(p.e) + (p.cat != null ? "  ·  " + p.cat : ""));
            iDesc.setText(p.desc != null ? p.desc : (p.sub != null ? p.sub : ""));
            Prog n = after(c, p);
            iNext.setText(n != null ? "Then  " + fmt(n.s) + "  " + n.t : "");
            boolean live = p.s <= t && p.e > t;
            iBar.setVisibility(live ? View.VISIBLE : View.INVISIBLE);
            if (live) setFill(iBarFill, V.dp(180), p);
        }
        iLogo.setImageDrawable(null);
        if (c.logo != null) {
            Bitmap b = Img.cached(c.logo, V.dp(64));
            if (b != null) iLogo.setImageBitmap(b);
        }
    }

    private void setFill(View fill, int width, Prog p) {
        long t = System.currentTimeMillis() / 1000;
        float f = p == null || p.e <= p.s ? 0 : Math.max(0, Math.min(1, (t - p.s) / (float) (p.e - p.s)));
        ViewGroup.LayoutParams lp = fill.getLayoutParams();
        lp.width = Math.round(width * f);
        fill.setLayoutParams(lp);
    }

    private boolean sameDay(long a, long b) { return DAY.format(a * 1000).equals(DAY.format(b * 1000)); }
    private String fmt(long ts) { return HM.format(ts * 1000); }

    static Prog[] curNext(Ch c, long t) {
        Prog cur = null, next = null;
        for (Prog p : c.progs) {
            if (p.s <= t && p.e > t) cur = p;
            else if (p.s > t && next == null) next = p;
        }
        return new Prog[] {cur, next};
    }

    private static Prog after(Ch c, Prog p) {
        int i = c.progs.indexOf(p);
        return i >= 0 && i + 1 < c.progs.size() ? c.progs.get(i + 1) : null;
    }

    // ------------------------------------------------------------------ keys

    private boolean okDown;

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (dropOpen()) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) { closeDrop(); return true; }
            return super.onKeyDown(code, e);
        }
        if (menuOpen()) {
            if (code == KeyEvent.KEYCODE_BACK) { closeMenu(); focusGrid(); return true; }
            return super.onKeyDown(code, e);
        }
        if (chipBar.hasFocus()) {
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) { focusGrid(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_UP) return true;
            return super.onKeyDown(code, e);
        }
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (mode == PLAYER) {
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_CHANNEL_UP: zap(-1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN: case KeyEvent.KEYCODE_CHANNEL_DOWN: zap(1); return true;
                case KeyEvent.KEYCODE_DPAD_LEFT: setMode(OVERLAY); onFocusChanged(); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: case KeyEvent.KEYCODE_INFO: showBanner(); return true;
                case KeyEvent.KEYCODE_MENU: openMenu(playing); return true;
                case KeyEvent.KEYCODE_BACK: setMode(GRID); return true;
            }
            if (center) { if (e.getRepeatCount() == 0) { e.startTracking(); okDown = true; } return true; }
            return super.onKeyDown(code, e);
        }
        // GRID / OVERLAY
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (mode == GRID && grid.atTop()) { focusChips(); return true; }
                grid.move(-1); onFocusChanged(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: grid.move(1); onFocusChanged(); return true;
            case KeyEvent.KEYCODE_CHANNEL_UP: case KeyEvent.KEYCODE_PAGE_UP: grid.move(-grid.visibleRows()); onFocusChanged(); return true;
            case KeyEvent.KEYCODE_CHANNEL_DOWN: case KeyEvent.KEYCODE_PAGE_DOWN: grid.move(grid.visibleRows()); onFocusChanged(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (!grid.step(-1)) { if (mode == GRID) focusChips(); } else onFocusChanged(); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: grid.step(1); onFocusChanged(); return true;
            case KeyEvent.KEYCODE_MENU: openMenu(grid.sel()); return true;
            case KeyEvent.KEYCODE_BACK:
                if (mode == OVERLAY) { setMode(PLAYER); return true; }
                if (grid.offNow()) { grid.toNow(); onFocusChanged(); return true; }
                finish(); return true;
        }
        if (center) { if (e.getRepeatCount() == 0) { e.startTracking(); okDown = true; } return true; }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            okDown = false;
            openMenu(mode == PLAYER ? playing : grid.sel());
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        boolean center = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (center && okDown && !dropOpen() && !menuOpen() && !chipBar.hasFocus()) {
            okDown = false;
            if (e.isCanceled()) return true;
            if (mode == PLAYER) { setMode(OVERLAY); onFocusChanged(); }
            else if (rows.isEmpty()) focusChips();
            else { Ch c = grid.sel(); if (c != null) watch(c); }
            return true;
        }
        return super.onKeyUp(code, e);
    }

    // ------------------------------------------------------------------ the grid

    final class Grid extends View {
        private List<Ch> list = new ArrayList<>();
        private int sel = 0, top = 0;
        private long cursor;          // time the focus follows across rows
        private long t0;              // left edge of the time axis
        private final float rowH = V.dpf(44), headH = V.dpf(28), chanW = V.dpf(196), pxMin = V.dpf(4.2f);
        private final float padX = V.dpf(V.edge(GuideActivity.this) * 0.75f);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint txt = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG), num = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final Rect clip = new Rect();

        Grid(Context c) {
            super(c);
            txt.setTypeface(V.font(c, 700)); txt.setTextSize(V.dpf(12.5f)); txt.setColor(V.TEXT);
            small.setTypeface(V.font(c, 600)); small.setTextSize(V.dpf(10.5f)); small.setColor(0x99FFFFFF);
            num.setTypeface(V.font(c, 700)); num.setTextSize(V.dpf(10.5f)); num.setColor(0x80FFFFFF);
            toNowEdge();
        }

        void setRows(List<Ch> l) {
            Ch keep = sel();
            list = new ArrayList<>(l);
            sel = 0; top = 0;
            if (keep != null) { int i = list.indexOf(keep); if (i >= 0) sel = i; }
            ensureVisible();
            invalidate();
        }

        boolean atTop() { return sel <= 0; }

        Ch sel() { return sel >= 0 && sel < list.size() ? list.get(sel) : null; }

        void focus(Ch c) {
            int i = list.indexOf(c);
            if (i >= 0) { sel = i; cursor = Math.max(cursor, now()); ensureVisible(); invalidate(); }
        }

        int visibleRows() { return Math.max(1, (int) ((getHeight() - headH) / rowH)); }

        private long now() { return System.currentTimeMillis() / 1000; }

        private void toNowEdge() {
            long n = now();
            t0 = n - (n % 1800);
            cursor = n;
        }

        boolean offNow() { return cursor > now() + 60 || t0 > leftEdge(); }
        void toNow() { toNowEdge(); invalidate(); }

        Prog selProg() {
            Ch c = sel();
            if (c == null) return null;
            if (cursor < now()) cursor = now();
            for (Prog p : c.progs) if (p.s <= cursor && p.e > cursor) return p;
            return null;
        }

        void move(int d) {
            if (list.isEmpty()) return;
            sel = Math.max(0, Math.min(list.size() - 1, sel + d));
            ensureVisible();
            invalidate();
        }

        private long leftEdge() { long n = now(); return n - (n % 1800); }

        /** Moves to the previous/next programme; returns false at the left edge. */
        boolean step(int d) {
            Ch c = sel();
            if (c == null) return d > 0;
            long n = now(), le = leftEdge();
            Prog p = selProg();
            if (d < 0) {
                if (p != null) {
                    if (p.s <= le) return false;
                    Prog prev = null;
                    for (Prog q : c.progs) if (q.e <= p.s) prev = q;
                    cursor = prev != null ? Math.max(prev.s, le) : Math.max(le, p.s - 1800);
                    if (cursor <= n && (prev == null || prev.e > n)) cursor = Math.max(cursor, n);
                } else {
                    if (cursor - 1800 < le) { if (cursor <= n + 60) return false; cursor = n; }
                    else cursor -= 1800;
                }
            } else {
                if (p != null) {
                    Prog nx = null;
                    for (Prog q : c.progs) if (q.s >= p.e) { nx = q; break; }
                    cursor = nx != null ? nx.s : p.e;
                } else cursor += 1800;
                cursor = Math.min(cursor, n + 3 * 86400);
            }
            keepCursorVisible();
            invalidate();
            return true;
        }

        private void keepCursorVisible() {
            float w = getWidth() - padX * 2 - chanW;
            long span = (long) (w / pxMin * 60);
            if (cursor < t0) t0 = cursor - (cursor % 1800);
            if (cursor > t0 + span - 1800) t0 = cursor - (cursor % 1800) - 1800;
            t0 = Math.max(t0, leftEdge());
        }

        private void ensureVisible() {
            int vis = visibleRows();
            if (sel < top + 1) top = Math.max(0, sel - 1);
            if (sel > top + vis - 2) top = Math.max(0, sel - vis + 2);
            top = Math.max(0, Math.min(top, Math.max(0, list.size() - vis)));
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) { ensureVisible(); }

        @Override
        protected void onDraw(Canvas cv) {
            float W = getWidth(), H = getHeight();
            t0 = Math.max(t0, leftEdge());
            float gx = padX + chanW;           // where programmes start
            float gw = W - padX - gx;
            long n = now();
            int accent = V.accent();
            boolean overlay = mode == OVERLAY;

            // time header
            small.setColor(0x99FFFFFF);
            for (long t = t0; ; t += 1800) {
                float x = gx + (t - t0) / 60f * pxMin;
                if (x > W - padX) break;
                cv.drawText(fmt(t), x + V.dpf(6), headH - V.dpf(10), small);
                fill.setColor(0x1FFFFFFF);
                cv.drawRect(x, headH - V.dpf(6), x + V.dpf(1), headH, fill);
            }
            if (sel() != null && !sameDay(t0, n)) cv.drawText(DAY.format(t0 * 1000), padX, headH - V.dpf(10), small);

            int vis = (int) Math.ceil((H - headH) / rowH);
            for (int i = 0; i < vis && top + i < list.size(); i++) {
                Ch c = list.get(top + i);
                float y = headH + i * rowH;
                boolean isSel = top + i == sel;
                // channel cell
                r.set(padX, y + V.dpf(2), padX + chanW - V.dpf(6), y + rowH - V.dpf(2));
                fill.setColor(isSel ? 0x2EFFFFFF : (overlay ? 0x14FFFFFF : 0x0DFFFFFF));
                cv.drawRoundRect(r, V.dpf(8), V.dpf(8), fill);
                num.setColor(c == playing ? accent : 0x80FFFFFF);
                cv.drawText(String.valueOf(c.num), padX + V.dpf(8), y + rowH / 2 + V.dpf(4), num);
                float lx = padX + V.dpf(34);
                if (c.logo != null) {
                    Bitmap b = Img.cached(c.logo, V.dp(64));
                    if (b != null) {
                        float bh = rowH - V.dpf(16), bw = Math.min(V.dpf(44), bh * b.getWidth() / Math.max(1f, b.getHeight()));
                        r.set(lx, y + (rowH - bh) / 2, lx + bw, y + (rowH + bh) / 2);
                        cv.drawBitmap(b, null, r, null);
                    } else if (!loading.contains(c.logo)) {
                        final String u = c.logo;
                        loading.add(u);
                        Img.load(GuideActivity.this, u, V.dp(64), bm -> { loading.remove(u); if (bm != null) invalidate(); });
                    }
                }
                txt.setColor(isSel ? V.TEXT : 0xD9FFFFFF);
                String nm = (favs.contains(c.key()) ? "★ " : "") + c.name;
                cv.drawText(TextUtils.ellipsize(nm, txt, chanW - V.dpf(98), TextUtils.TruncateAt.END).toString(),
                        lx + V.dpf(50), y + rowH / 2 + V.dpf(4.5f), txt);

                // programmes
                cv.save();
                cv.clipRect(gx, y, W - padX, y + rowH);
                boolean any = false;
                for (Prog p : c.progs) {
                    float x1 = gx + (p.s - t0) / 60f * pxMin, x2 = gx + (p.e - t0) / 60f * pxMin;
                    if (x2 < gx || x1 > W - padX) continue;
                    any = true;
                    boolean focused = isSel && p.s <= cursor && p.e > cursor;
                    boolean live = p.s <= n && p.e > n;
                    r.set(Math.max(x1, gx - V.dpf(8)) + V.dpf(2), y + V.dpf(2), x2 - V.dpf(2), y + rowH - V.dpf(2));
                    fill.setColor(focused ? accent : live ? (overlay ? 0x29FFFFFF : 0x1FFFFFFF) : (overlay ? 0x14FFFFFF : 0x0DFFFFFF));
                    cv.drawRoundRect(r, V.dpf(8), V.dpf(8), fill);
                    float tx = Math.max(x1, gx) + V.dpf(10), avail = x2 - V.dpf(10) - tx;
                    if (avail > V.dpf(14)) {
                        txt.setColor(focused ? V.INK : (p.e <= n ? 0x73FFFFFF : 0xE6FFFFFF));
                        cv.drawText(TextUtils.ellipsize(p.t, txt, avail, TextUtils.TruncateAt.END).toString(), tx, y + rowH / 2 - V.dpf(1), txt);
                        small.setColor(focused ? 0xB3000000 : 0x80FFFFFF);
                        cv.drawText(TextUtils.ellipsize(fmt(p.s) + " – " + fmt(p.e), small, avail, TextUtils.TruncateAt.END).toString(), tx, y + rowH / 2 + V.dpf(13), small);
                    }
                }
                if (!any) {
                    r.set(gx + V.dpf(2), y + V.dpf(2), W - padX - V.dpf(2), y + rowH - V.dpf(2));
                    fill.setColor(isSel ? accent : (overlay ? 0x14FFFFFF : 0x0DFFFFFF));
                    cv.drawRoundRect(r, V.dpf(8), V.dpf(8), fill);
                    txt.setColor(isSel ? V.INK : 0x99FFFFFF);
                    cv.drawText(c.guide ? c.name : c.name + "  ·  no TV guide", gx + V.dpf(12), y + rowH / 2 + V.dpf(4.5f), txt);
                }
                cv.restore();
            }

            // now line
            float nx = gx + (n - t0) / 60f * pxMin;
            if (nx >= gx && nx <= W - padX) {
                fill.setColor(0xFFFF5A5A);
                cv.drawRect(nx - V.dpf(1), headH - V.dpf(4), nx + V.dpf(1), H, fill);
                cv.drawCircle(nx, headH - V.dpf(4), V.dpf(3.5f), fill);
            }
            // fade the bottom edge
            if (!overlay) {
                fill.setShader(new LinearGradient(0, H - V.dpf(12), 0, H, 0x0007080B, 0xFF07080B, Shader.TileMode.CLAMP));
                cv.drawRect(0, H - V.dpf(12), W, H, fill);
                fill.setShader(null);
            }
        }

        private final Set<String> loading = new HashSet<>();
    }
}
