package com.voteitapp.voteit;


import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.Manifest;
import android.content.pm.PackageManager;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.activity.EdgeToEdge;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapEventsReceiver;
import org.osmdroid.api.IGeoPoint;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.Marker;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    private static final int LOCATION_PERMISSION_CODE = 1;
    private static final String PREFS_NAME  = "map_prefs";
    private static final String KEY_LAT     = "last_lat";
    private static final String KEY_LON     = "last_lon";
    private static final String KEY_NAME    = "last_name";
    private static final String DELIMITER   = "\t";

    private static final String SUPABASE_URL   = BuildConfig.SUPABASE_URL.replaceAll("/$", "");
    private static final String SUPABASE_TABLE = "pins";

    private static final long SYNC_INTERVAL_MS = TimeUnit.SECONDS.toMillis(20);

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private FusedLocationProviderClient fusedLocationClient;
    private MapView map;
    private boolean addingPinMode = false;
    private GeoPoint selectedPoint = null;

    private String sessionToken        = "";
    private String sessionRefreshToken = "";
    private String sessionUserId       = "";
    private String sessionUsername     = "";

    /** true gdy dialog wyboru miasta jest już wyświetlony — zapobiega podwójnemu pokazaniu */
    private boolean cityDialogShown = false;
    private Marker userLocationMarker = null;

    /** Callback do ciągłego śledzenia pozycji */
    private com.google.android.gms.location.LocationCallback locationCallback = null;
    /** true = mapa już wycentrowała się na użytkowniku przy starcie */
    private boolean initialCenterDone = false;

    /**
     * Centrum wokół którego szukamy pinezek.
     * Zmienia się przy wyszukaniu miasta LUB przy powrocie do GPS.
     * Domyślnie = pozycja GPS użytkownika.
     */
    private double searchCenterLat = 0;
    private double searchCenterLon = 0;

    private final Handler syncHandler  = new Handler(Looper.getMainLooper());
    private final Runnable syncRunnable = new Runnable() {
        @Override public void run() {
            syncPointsFromSupabase();
            syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        loadSession();
        // Zastosuj zapisane ustawienia (motyw + język) PRZED setContentView
        // żeby Activity od razu startowała z właściwym motywem i językiem
        applyStoredSettings();
        createNotificationChannel();

        EdgeToEdge.enable(this);
        Configuration.getInstance().setUserAgentValue(getPackageName());
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            androidx.core.graphics.Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        map = findViewById(R.id.map);
        map.setMultiTouchControls(true);
        map.setBuiltInZoomControls(false);
        map.getController().setZoom(18.0);

        GeoPoint last = loadLastLocation();
        if (last != null) {
            map.getController().setCenter(last);
            showNearbyPoints(last.getLatitude(), last.getLongitude());
        }
        // Zawsze próbuj startować śledzenie GPS od razu
        checkLocationPermission();

        MapEventsReceiver receiver = new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (addingPinMode) {
                    selectedPoint = p;
                    showAddPinSheet();
                    addingPinMode = false;
                    return true;
                }
                return false;
            }
            @Override public boolean longPressHelper(GeoPoint p) { return false; }
        };
        map.getOverlays().add(new MapEventsOverlay(receiver));

        findViewById(R.id.searchCard).setOnClickListener(v -> showSearchSheet());

        findViewById(R.id.refreshBtn).setOnClickListener(v -> {
            v.animate().rotation(v.getRotation() + 360f).setDuration(600).start();
            Toast.makeText(this, getString(R.string.refreshing), Toast.LENGTH_SHORT).show();

            // Usuń wszystkie pinezki i załaduj od nowa z aktualnego centrum wyszukiwania
            for (int i = map.getOverlays().size() - 1; i >= 0; i--) {
                org.osmdroid.views.overlay.Overlay o = map.getOverlays().get(i);
                if (o instanceof Marker && o != userLocationMarker)
                    map.getOverlays().remove(i);
            }
            map.invalidate();
            syncPointsFromSupabase();
        });

        ((FloatingActionButton) findViewById(R.id.addPinBtn)).setOnClickListener(v -> {
            if (!isLoggedIn()) {
                Toast.makeText(this, getString(R.string.login_required_pin), Toast.LENGTH_SHORT).show();
                goToLogin();
                return;
            }
            addingPinMode = true;
            Toast.makeText(this, getString(R.string.tap_map_to_pin), Toast.LENGTH_SHORT).show();
        });

        ((FloatingActionButton) findViewById(R.id.rightBtn)).setOnClickListener(v -> {
            if (userLocationMarker != null) {
                GeoPoint userPos = userLocationMarker.getPosition();
                searchCenterLat = userPos.getLatitude();
                searchCenterLon = userPos.getLongitude();
                map.getController().animateTo(userPos, 20.0, 500L);
                showNearbyPoints(searchCenterLat, searchCenterLon);
            } else if (hasLocationPermission()) {
                Toast.makeText(this, getString(R.string.gps_locating), Toast.LENGTH_SHORT).show();
                // Pobierz keszowaną pozycję raz jeszcze
                tryShowCachedLocation();
            } else {
                checkLocationPermission();
            }
        });

        ((FloatingActionButton) findViewById(R.id.leftBtn)).setOnClickListener(v ->
                showTopPinsSheet());

        ((FloatingActionButton) findViewById(R.id.userBtn)).setOnClickListener(v -> {
            if (isLoggedIn()) showProfileSheet();
            else goToLogin();
        });

        ((FloatingActionButton) findViewById(R.id.settingsBtn)).setOnClickListener(v ->
                showSettingsSheet());

        syncHandler.post(syncRunnable);
    }

    @Override protected void onResume() {
        super.onResume();
        map.onResume();
        loadSession();
        // Wznów śledzenie jeśli mamy uprawnienia
        if (hasLocationPermission()) startContinuousLocationUpdates();
    }

    @Override protected void onPause() {
        super.onPause();
        map.onPause();
        IGeoPoint center = map.getMapCenter();
        saveLastLocation(center.getLatitude(), center.getLongitude(), "");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        syncHandler.removeCallbacks(syncRunnable);
        stopContinuousLocationUpdates();
    }


    // -------------------------------------------------------------------------
    // Session helpers
    // -------------------------------------------------------------------------

    private void loadSession() {
        SharedPreferences prefs = getSharedPreferences(LoginActivity.PREFS_AUTH, MODE_PRIVATE);
        sessionToken        = prefs.getString(LoginActivity.KEY_TOKEN,         "");
        sessionRefreshToken = prefs.getString(LoginActivity.KEY_REFRESH_TOKEN, "");
        sessionUserId       = prefs.getString(LoginActivity.KEY_USER_ID,       "");
        sessionUsername     = prefs.getString(LoginActivity.KEY_USERNAME,      "");
    }

    private boolean isLoggedIn() {
        return !sessionToken.isEmpty();
    }

    private void logout() {
        getSharedPreferences(LoginActivity.PREFS_AUTH, MODE_PRIVATE).edit().clear().apply();
        sessionToken        = "";
        sessionRefreshToken = "";
        sessionUserId       = "";
        sessionUsername     = "";
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra("can_go_back", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void goToLogin() {
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra("can_go_back", true);
        startActivity(intent);
    }

    /**
     * Odświeża access_token używając refresh_token.
     * Wywołuj NA WĄTKU ROBOCZYM.
     */
    private boolean refreshSessionIfNeeded() {
        if (sessionRefreshToken.isEmpty()) return false;
        try {
            URL url = new URL(SUPABASE_URL + "/auth/v1/token?grant_type=refresh_token");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("apikey",       BuildConfig.SUPABASE_ANON_KEY);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            JSONObject body = new JSONObject();
            body.put("refresh_token", sessionRefreshToken);
            conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

            int code = conn.getResponseCode();
            if (code == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONObject json = new JSONObject(sb.toString());
                String newToken        = json.optString("access_token",  "");
                String newRefreshToken = json.optString("refresh_token", "");

                if (!newToken.isEmpty()) {
                    sessionToken        = newToken;
                    if (!newRefreshToken.isEmpty()) sessionRefreshToken = newRefreshToken;

                    getSharedPreferences(LoginActivity.PREFS_AUTH, MODE_PRIVATE).edit()
                            .putString(LoginActivity.KEY_TOKEN,         newToken)
                            .putString(LoginActivity.KEY_REFRESH_TOKEN, sessionRefreshToken)
                            .apply();

                    android.util.Log.d("VoteIt", "Token odświeżony pomyślnie");
                    return true;
                }
            }
            conn.disconnect();
            runOnUiThread(this::logout);
            return false;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkLocationPermission() {
        if (hasLocationPermission()) getUserLocation();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION},
                LOCATION_PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == LOCATION_PERMISSION_CODE) {
            boolean granted = false;
            for (int r : results) if (r == PackageManager.PERMISSION_GRANTED) { granted = true; break; }
            if (granted) getUserLocation(); else showCityInputDialog();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void getUserLocation() {
        if (!hasLocationPermission()) return;

        // Krok 1: natychmiast pokaż ostatnio keszowaną pozycję systemu (0 czekania)
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, loc -> {
            if (loc != null) {
                double lat = loc.getLatitude();
                double lon = loc.getLongitude();
                // Pokaż ikonkę od razu — nawet jeśli dokładność słaba
                runOnUiThread(() -> {
                    updateUserLocationMarker(lat, lon);
                    if (!initialCenterDone) {
                        initialCenterDone = true;
                        searchCenterLat = lat;
                        searchCenterLon = lon;
                        map.getController().setZoom(18.0);
                        map.getController().animateTo(new GeoPoint(lat, lon));
                        saveLastLocation(lat, lon, "Twoja lokalizacja");
                        showNearbyPoints(lat, lon);
                    }
                });
            }
        });

        // Krok 2: startuj ciągłe śledzenie które będzie precyzować pozycję na bieżąco
        startContinuousLocationUpdates();
    }

    /**
     * Startuje ciągłe aktualizacje pozycji GPS.
     * Wywoływane raz przy starcie i po przyznaniu uprawnień.
     * Marker lokalizacji aktualizuje się automatycznie co ~3 sekundy.
     * Przy pierwszym otrzymaniu pozycji mapa centruje się na użytkowniku.
     */
    @SuppressWarnings("MissingPermission")
    private void startContinuousLocationUpdates() {
        if (!hasLocationPermission()) return;
        if (locationCallback != null) return; // już działa

        com.google.android.gms.location.LocationRequest req =
                com.google.android.gms.location.LocationRequest.create()
                        .setPriority(com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY)
                        .setInterval(3000)          // aktualizuj co 3 sekundy
                        .setFastestInterval(1000);  // max co 1 sekundę

        locationCallback = new com.google.android.gms.location.LocationCallback() {
            @Override
            public void onLocationResult(@NonNull com.google.android.gms.location.LocationResult result) {
                android.location.Location loc = result.getLastLocation();
                if (loc == null) return;

                double lat = loc.getLatitude();
                double lon = loc.getLongitude();

                // Ignoruj aktualizacje gdy dokładność jest słaba (>25m szumu)
                if (loc.hasAccuracy() && loc.getAccuracy() > 25f) return;

                // Ignoruj mikroruchy — aktualizuj marker tylko gdy przesunięto się >15m
                if (userLocationMarker != null) {
                    GeoPoint current = userLocationMarker.getPosition();
                    float[] dist = new float[1];
                    android.location.Location.distanceBetween(
                            current.getLatitude(), current.getLongitude(), lat, lon, dist);
                    if (dist[0] < 15f) return; // poniżej 15m — ignoruj
                }

                // Zawsze aktualizuj marker lokalizacji
                runOnUiThread(() -> updateUserLocationMarker(lat, lon));

                // Tylko przy pierwszym otrzymaniu pozycji — centruj mapę i załaduj pinezki
                if (!initialCenterDone) {
                    initialCenterDone = true;
                    runOnUiThread(() -> {
                        searchCenterLat = lat;
                        searchCenterLon = lon;
                        map.getController().setZoom(18.0);
                        map.getController().animateTo(new GeoPoint(lat, lon));
                        saveLastLocation(lat, lon, "Twoja lokalizacja");
                        showNearbyPoints(lat, lon);
                    });
                }
            }
        };

        fusedLocationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
    }

    /** Zatrzymuje śledzenie GPS — wywoływane w onDestroy */
    private void stopContinuousLocationUpdates() {
        if (locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    // -------------------------------------------------------------------------
    // Bottom sheets
    // -------------------------------------------------------------------------

    private void showCityInputDialog() {
        if (cityDialogShown) return;
        cityDialogShown = true;
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_search, null);
        dialog.setContentView(view);
        EditText input = view.findViewById(R.id.searchInput);
        Button btn = view.findViewById(R.id.searchGoBtn);
        btn.setOnClickListener(v -> {
            String city = input.getText().toString().trim();
            if (!city.isEmpty()) { performSearch(city, null, null); dialog.dismiss(); }
        });
        dialog.setOnDismissListener(d -> cityDialogShown = false);
        dialog.show();
    }

    private void showSearchSheet() {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_search, null);
        dialog.setContentView(view);
        EditText input      = view.findViewById(R.id.searchInput);
        Button goBtn        = view.findViewById(R.id.searchGoBtn);
        RecyclerView list   = view.findViewById(R.id.searchResultsList);
        View divider        = view.findViewById(R.id.searchDivider);
        TextView statusText = view.findViewById(R.id.searchStatusText);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setNestedScrollingEnabled(false);
        goBtn.setOnClickListener(v -> {
            String query = input.getText().toString().trim();
            if (query.isEmpty()) return;
            statusText.setText(getString(R.string.search_loading));
            statusText.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
            performSearch(query, results -> {
                statusText.setVisibility(View.GONE);
                divider.setVisibility(View.VISIBLE);
                list.setVisibility(View.VISIBLE);
                list.setAdapter(new SearchResultAdapter(results, item -> {
                    moveToLocation(item.lat, item.lon, item.displayName);
                    dialog.dismiss();
                }));
            }, () -> {
                list.setVisibility(View.GONE);
                divider.setVisibility(View.GONE);
                statusText.setText(getString(R.string.search_empty));
                statusText.setVisibility(View.VISIBLE);
            });
        });
        dialog.show();
    }

    private void showAddPinSheet() {
        if (selectedPoint == null) return;
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        // Keyboard pushes the sheet up instead of hiding it
        dialog.getBehavior().setSkipCollapsed(false);
        dialog.getBehavior().setState(
                com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);

        View view = getLayoutInflater().inflate(R.layout.bottom_sheet, null);
        dialog.setContentView(view);

        // Allow sheet to resize when keyboard appears
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        EditText pinName = view.findViewById(R.id.pinName);
        EditText pinDesc = view.findViewById(R.id.pinDesc);
        Button saveBtn   = view.findViewById(R.id.savePinBtn);

        dialog.setOnShowListener(d -> {
            int sheetId = getResources().getIdentifier(
                    "design_bottom_sheet", "id", getPackageName());
            View bottomSheet = dialog.findViewById(sheetId);
            if (bottomSheet == null) {
                bottomSheet = dialog.getWindow().getDecorView()
                        .findViewById(android.R.id.content);
            }
            if (bottomSheet != null) {
                com.google.android.material.bottomsheet.BottomSheetBehavior<View> behavior =
                        com.google.android.material.bottomsheet.BottomSheetBehavior.from(bottomSheet);
                bottomSheet.getLayoutParams().height =
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                behavior.setPeekHeight(android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
                behavior.setState(
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
            }
        });

        saveBtn.setOnClickListener(v -> {
            String name = sanitiseInput(pinName.getText().toString());
            String desc = sanitiseInput(pinDesc.getText().toString());
            if (name.isEmpty()) { pinName.setError(getString(R.string.profile_username_empty)); return; }
            savePointToFile(selectedPoint.getLatitude(), selectedPoint.getLongitude(), name, desc, "", 0, 0);
            uploadPointToSupabase(selectedPoint.getLatitude(), selectedPoint.getLongitude(), name, desc);
            dialog.dismiss();
        });
        dialog.show();
    }

    /**
     * Szczegóły pinezki z głosowaniem.
     * Przycisk głosowania blokuje się do czasu załadowania aktualnego głosu z bazy.
     * Przycisk delete widoczny tylko dla autora pinezki.
     */
    private void showPinDetailSheet(Marker marker) {
        BottomSheetDialog dialog = new BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.bottom_sheet_pin, null);
        view.setBackgroundColor(colorBg());
        dialog.setContentView(view);

        TextView titleView     = view.findViewById(R.id.pinDetailTitle);
        TextView upvoteCount   = view.findViewById(R.id.pinUpvoteCount);
        TextView downvoteCount = view.findViewById(R.id.pinDownvoteCount);
        TextView descView      = view.findViewById(R.id.pinDetailDescription);
        Button upvoteBtn       = view.findViewById(R.id.pinNavigateBtn);
        Button downvoteBtn     = view.findViewById(R.id.pinDeleteBtn);
        Button deleteBtn       = view.findViewById(R.id.pinOwnerDeleteBtn);
        TextView clearVoteBtn  = view.findViewById(R.id.pinClearVoteBtn);
        TextView favBtn        = view.findViewById(R.id.pinFavBtn);
        TextView authorAvatar  = view.findViewById(R.id.pinAuthorAvatar);
        TextView authorName    = view.findViewById(R.id.pinAuthorName);

        String markerTitle = marker.getTitle();
        String cleanTitle  = (markerTitle != null && markerTitle.contains("  "))
                ? markerTitle.substring(0, markerTitle.lastIndexOf("  "))
                : (markerTitle != null ? markerTitle : "Pinezka");
        titleView.setText(cleanTitle);
        titleView.setTextColor(colorTextMain());

        // Parse snippet "upvotes|downvotes|authorId"
        String snippet   = marker.getSnippet();
        int    upvotes   = 0;
        int    downvotes = 0;
        String authorId  = "";
        if (snippet != null) {
            String[] parts = snippet.split("\\|", 3);
            try { upvotes   = Integer.parseInt(parts[0]); } catch (Exception ignored) {}
            if (parts.length > 1) { try { downvotes = Integer.parseInt(parts[1]); } catch (Exception ignored) {} }
            if (parts.length > 2) authorId = parts[2];
        }

        upvoteCount.setText(String.valueOf(upvotes));
        upvoteCount.setTextColor(0xFF4CAF50);
        downvoteCount.setText(String.valueOf(downvotes));
        downvoteCount.setTextColor(0xFFFF5252);

        // Opis — tło reagujące na dark mode
        androidx.cardview.widget.CardView descCard = view.findViewById(R.id.pinDescCard);
        if (descCard != null) descCard.setCardBackgroundColor(colorDescBg());
        String sub = marker.getSubDescription();
        descView.setText((sub != null && !sub.isEmpty()) ? sub : getString(R.string.pin_no_desc));
        descView.setTextColor(colorDescText());
        view.findViewById(R.id.pinDetailDescription).setBackgroundColor(0); // transparent; card handles bg

        // Autor — pobierz username z profiles
        if (authorAvatar != null && authorName != null) {
            android.graphics.drawable.GradientDrawable avatarCircle =
                    new android.graphics.drawable.GradientDrawable();
            avatarCircle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            avatarCircle.setColor(0xFFFF5252);
            authorAvatar.setBackground(avatarCircle);
            authorName.setText(getString(R.string.pin_loading_author));

            final String fetchAuthorId = authorId;
            new Thread(() -> {
                try {
                    String username = "Nieznany";
                    if (!fetchAuthorId.isEmpty()) {
                        URL url = new URL(SUPABASE_URL + "/rest/v1/profiles"
                                + "?id=eq." + fetchAuthorId + "&select=username&limit=1");
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setRequestMethod("GET");
                        c.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                        c.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
                        if (c.getResponseCode() == 200) {
                            StringBuilder sb = new StringBuilder();
                            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                                String line; while ((line = r.readLine()) != null) sb.append(line);
                            }
                            JSONArray arr = new JSONArray(sb.toString());
                            if (arr.length() > 0) username = arr.getJSONObject(0).optString("username", "Nieznany");
                        }
                        c.disconnect();
                    }
                    final String finalUsername = username;
                    runOnUiThread(() -> {
                        String initial = finalUsername.isEmpty() ? "?" : finalUsername.substring(0, 1).toUpperCase();
                        authorAvatar.setText(initial);
                        authorName.setText("📍 " + finalUsername);
                    });
                } catch (Exception e) { e.printStackTrace(); }
            }).start();
        }

        String pinId = (String) marker.getRelatedObject();

        // Pobierz aktualne głosy z bazy zaraz po otwarciu sheetu
        // (snippet na markerze może być nieaktualny jeśli głosowano z innego urządzenia)
        {
            final String livePinId = pinId;
            if (livePinId != null && !livePinId.isEmpty()) {
                new Thread(() -> {
                    try {
                        URL voteUrl = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                                + "?id=eq." + livePinId + "&select=upvotes%2Cdownvotes&limit=1");
                        HttpURLConnection vc = (HttpURLConnection) voteUrl.openConnection();
                        vc.setRequestMethod("GET");
                        vc.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                        vc.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
                        if (vc.getResponseCode() == 200) {
                            StringBuilder vsb = new StringBuilder();
                            try (BufferedReader vr = new BufferedReader(
                                    new InputStreamReader(vc.getInputStream()))) {
                                String vl; while ((vl = vr.readLine()) != null) vsb.append(vl);
                            }
                            JSONArray vrows = new JSONArray(vsb.toString());
                            if (vrows.length() > 0) {
                                final int liveUp   = vrows.getJSONObject(0).optInt("upvotes",   0);
                                final int liveDown = vrows.getJSONObject(0).optInt("downvotes", 0);
                                runOnUiThread(() -> {
                                    upvoteCount.setText(String.valueOf(liveUp));
                                    downvoteCount.setText(String.valueOf(liveDown));
                                    String oldSnip = marker.getSnippet();
                                    String aid = "";
                                    if (oldSnip != null) {
                                        String[] sp = oldSnip.split("\\|", 3);
                                        aid = sp.length > 2 ? sp[2] : "";
                                    }
                                    marker.setSnippet(liveUp + "|" + liveDown + "|" + aid);
                                });
                            }
                        }
                        vc.disconnect();
                    } catch (Exception ignored) {}
                }).start();
            }
        }

        // Serce — ulubione
        if (favBtn != null && pinId != null) {
            if (!isLoggedIn()) {
                favBtn.setText("♡");
                favBtn.setTextColor(0xFFCCCCCC);
                favBtn.setOnClickListener(v -> { dialog.dismiss(); goToLogin(); });
            } else {
                favBtn.setText("♡");
                favBtn.setTextColor(0xFFCCCCCC);
                // Sprawdź czy już ulubiona
                final String checkPinId = pinId;
                new Thread(() -> {
                    try {
                        refreshSessionIfNeeded();
                        URL url = new URL(SUPABASE_URL + "/rest/v1/pin_favourites"
                                + "?pin_id=eq." + checkPinId + "&user_id=eq." + sessionUserId
                                + "&select=id&limit=1");
                        HttpURLConnection c = (HttpURLConnection) url.openConnection();
                        c.setRequestMethod("GET");
                        c.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                        c.setRequestProperty("Authorization", "Bearer " + sessionToken);
                        boolean isFav = false;
                        if (c.getResponseCode() == 200) {
                            StringBuilder sb = new StringBuilder();
                            try (java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(c.getInputStream()))) {
                                String line; while ((line = r.readLine()) != null) sb.append(line);
                            }
                            isFav = new org.json.JSONArray(sb.toString()).length() > 0;
                        }
                        c.disconnect();
                        final boolean finalFav = isFav;
                        runOnUiThread(() -> {
                            favBtn.setText(finalFav ? "♥" : "♡");
                            favBtn.setTextColor(finalFav ? 0xFFFF5252 : 0xFFCCCCCC);
                        });
                    } catch (Exception e) { e.printStackTrace(); }
                }).start();
                favBtn.setOnClickListener(v -> toggleFavourite(checkPinId, favBtn));
            }
        }

        // Delete button — author only
        if (deleteBtn != null) {
            boolean isAuthor = isLoggedIn() && !authorId.isEmpty() && authorId.equals(sessionUserId);
            deleteBtn.setVisibility(isAuthor ? View.VISIBLE : View.GONE);
            if (isAuthor) {
                deleteBtn.setOnClickListener(v -> {
                    confirmAndDeletePin(pinId, marker);
                    dialog.dismiss();
                });
            }
        }

        if (!isLoggedIn()) {
            upvoteBtn.setEnabled(true);
            downvoteBtn.setEnabled(true);
            upvoteBtn.setAlpha(0.5f);
            downvoteBtn.setAlpha(0.5f);
            upvoteBtn.setText(getString(R.string.vote_login));
            downvoteBtn.setText(getString(R.string.vote_login_down));
            View.OnClickListener goLogin = v -> { dialog.dismiss(); goToLogin(); };
            upvoteBtn.setOnClickListener(goLogin);
            downvoteBtn.setOnClickListener(goLogin);
            dialog.show();
            return;
        }

        // Zablokuj przyciski do czasu załadowania aktualnego głosu z bazy
        upvoteBtn.setEnabled(false);
        downvoteBtn.setEnabled(false);
        upvoteBtn.setAlpha(0.4f);
        downvoteBtn.setAlpha(0.4f);

        final int[] currentVote = {Integer.MIN_VALUE};
        if (pinId != null && !pinId.isEmpty()) {
            new Thread(() -> {
                try {
                    refreshSessionIfNeeded();
                    URL url = new URL(SUPABASE_URL + "/rest/v1/pin_votes"
                            + "?pin_id=eq." + pinId
                            + "&user_id=eq." + sessionUserId
                            + "&select=value&limit=1");
                    HttpURLConnection c = (HttpURLConnection) url.openConnection();
                    c.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                    c.setRequestProperty("Authorization", "Bearer " + sessionToken);
                    if (c.getResponseCode() == 200) {
                        StringBuilder sb = new StringBuilder();
                        try (BufferedReader r = new BufferedReader(
                                new InputStreamReader(c.getInputStream()))) {
                            String line; while ((line = r.readLine()) != null) sb.append(line);
                        }
                        JSONArray rows = new JSONArray(sb.toString());
                        currentVote[0] = rows.length() > 0
                                ? rows.getJSONObject(0).optInt("value", 0)
                                : 0;
                    } else {
                        currentVote[0] = 0;
                    }
                    c.disconnect();
                } catch (Exception ignored) {
                    currentVote[0] = 0;
                }
                runOnUiThread(() -> {
                    applyVoteButtonState(upvoteBtn, downvoteBtn, currentVote[0]);
                    clearVoteBtn.setVisibility(currentVote[0] != 0 ? View.VISIBLE : View.GONE);

                    upvoteBtn.setOnClickListener(v -> {
                        if (pinId == null) return;
                        if (currentVote[0] == 1) return;
                        currentVote[0] = 1;
                        applyVoteButtonState(upvoteBtn, downvoteBtn, 1);
                        clearVoteBtn.setVisibility(View.VISIBLE);
                        castVoteWithView(pinId, 1, upvoteCount, downvoteCount);
                        Toast.makeText(this, getString(R.string.voted_up), Toast.LENGTH_SHORT).show();
                    });

                    downvoteBtn.setOnClickListener(v -> {
                        if (pinId == null) return;
                        if (currentVote[0] == -1) return;
                        currentVote[0] = -1;
                        applyVoteButtonState(upvoteBtn, downvoteBtn, -1);
                        clearVoteBtn.setVisibility(View.VISIBLE);
                        castVoteWithView(pinId, -1, upvoteCount, downvoteCount);
                        Toast.makeText(this, getString(R.string.voted_down), Toast.LENGTH_SHORT).show();
                    });

                    clearVoteBtn.setOnClickListener(v -> {
                        if (pinId == null) return;
                        currentVote[0] = 0;
                        applyVoteButtonState(upvoteBtn, downvoteBtn, 0);
                        clearVoteBtn.setVisibility(View.GONE);
                        removeVote(pinId, upvoteCount, downvoteCount);
                        Toast.makeText(this, getString(R.string.vote_removed), Toast.LENGTH_SHORT).show();
                    });
                });
            }).start();
        } else {
            upvoteBtn.setEnabled(false);
            downvoteBtn.setEnabled(false);
            upvoteBtn.setText(getString(R.string.vote_syncing_up));
            downvoteBtn.setText(getString(R.string.vote_syncing_down));
        }

        dialog.show();
    }

    /**
     * Applies vote button state:
     * - Active vote → button disabled (locked), full opacity, checkmark label.
     * - Opposite button → enabled, full opacity.
     * - No vote → both enabled, slightly dimmed.
     */
    private void applyVoteButtonState(Button upBtn, Button downBtn, int currentVote) {
        switch (currentVote) {
            case 1:
                upBtn.setEnabled(false);
                upBtn.setAlpha(1f);
                upBtn.setText(getString(R.string.upvote_done));
                downBtn.setEnabled(true);
                downBtn.setAlpha(1f);
                downBtn.setText(getString(R.string.downvote));
                break;
            case -1:
                downBtn.setEnabled(false);
                downBtn.setAlpha(1f);
                downBtn.setText(getString(R.string.downvote_done));
                upBtn.setEnabled(true);
                upBtn.setAlpha(1f);
                upBtn.setText(getString(R.string.upvote));
                break;
            default:
                upBtn.setEnabled(true);
                upBtn.setAlpha(0.7f);
                upBtn.setText(getString(R.string.upvote));
                downBtn.setEnabled(true);
                downBtn.setAlpha(0.7f);
                downBtn.setText(getString(R.string.downvote));
                break;
        }
    }

    private void confirmAndDeletePin(String pinId, Marker marker) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.pin_delete_title))
                .setMessage(getString(R.string.pin_delete_message))
                .setPositiveButton(getString(R.string.pin_delete_confirm), (d, w) -> deletePin(pinId, marker))
                .setNegativeButton(getString(R.string.pin_delete_cancel), null)
                .show();
    }

    private void deletePin(String pinId, Marker marker) {
        if (pinId == null || pinId.isEmpty()) {
            Toast.makeText(this, getString(R.string.pin_delete_error_no_id), Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            try {
                refreshSessionIfNeeded();
                android.util.Log.d("VoteIt", "deletePin token prefix=" + (sessionToken.length() > 20 ? sessionToken.substring(0, 20) : sessionToken));
                android.util.Log.d("VoteIt", "deletePin userId=" + sessionUserId);
                URL url = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                        + "?id=eq." + pinId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("DELETE");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                conn.setRequestProperty("Content-Type",  "application/json");
                conn.setRequestProperty("Prefer",        "return=minimal");

                int code = conn.getResponseCode();

                java.io.InputStream stream = (code >= 400) ? conn.getErrorStream() : conn.getInputStream();
                String responseBody = "";
                if (stream != null) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(stream))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    responseBody = sb.toString();
                }
                conn.disconnect();

                android.util.Log.d("VoteIt", "deletePin code=" + code + " body=" + responseBody);

                if (code == 200 || code == 204) {
                    runOnUiThread(() -> {
                        map.getOverlays().remove(marker);
                        map.invalidate();
                        Toast.makeText(this, getString(R.string.pin_deleted), Toast.LENGTH_SHORT).show();
                        syncPointsFromSupabase();
                    });
                } else if (code == 403) {
                    runOnUiThread(() ->
                            Toast.makeText(this, getString(R.string.pin_delete_no_permission), Toast.LENGTH_LONG).show());
                } else {
                    final String errBody = responseBody;
                    runOnUiThread(() ->
                            Toast.makeText(this, "Błąd " + code + ": " + errBody, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(this, "Błąd połączenia: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Profil — bottom sheet
    // -------------------------------------------------------------------------

    private void showProfileSheet() {
        String displayName = sessionUsername.isEmpty() ? "Użytkownik" : sessionUsername;
        float density = getResources().getDisplayMetrics().density;
        int dp16 = (int)(16 * density), dp8 = (int)(8 * density);

        // Wykryj motyw — ciemny czy jasny
        boolean isDark = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int colorText      = isDark ? 0xFFEEEEEE : 0xFF1A1A1A;
        int colorSubtext   = isDark ? 0xFFAAAAAA : 0xFF888888;
        int colorCardBg    = isDark ? 0xFF2C2C2C : 0xFFF5F5F5;
        int colorDivider   = isDark ? 0x22FFFFFF : 0x12000000;

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setPadding(dp16, dp16, dp16, dp16 * 2);

        // Handle
        View handle = new View(this);
        android.widget.LinearLayout.LayoutParams handleParams =
                new android.widget.LinearLayout.LayoutParams((int)(40 * density), (int)(4 * density));
        handleParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        handleParams.bottomMargin = dp16;
        handle.setLayoutParams(handleParams);
        handle.setBackgroundColor(colorDivider);
        container.addView(handle);

        // Avatar + nazwa + przycisk edycji
        android.widget.LinearLayout headerRow = new android.widget.LinearLayout(this);
        headerRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        headerRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams headerParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.bottomMargin = dp16;
        headerRow.setLayoutParams(headerParams);

        // Kółko z inicjałem
        TextView avatarView = new TextView(this);
        int avatarSize = (int)(52 * density);
        android.widget.LinearLayout.LayoutParams avParams =
                new android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize);
        avParams.rightMargin = dp16;
        avatarView.setLayoutParams(avParams);
        avatarView.setGravity(android.view.Gravity.CENTER);
        avatarView.setText(displayName.substring(0, 1).toUpperCase());
        avatarView.setTextSize(22);
        avatarView.setTextColor(0xFFFFFFFF);
        avatarView.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(0xFFFF5252);
        avatarView.setBackground(circle);
        headerRow.addView(avatarView);

        android.widget.LinearLayout nameCol = new android.widget.LinearLayout(this);
        nameCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams nameColParams =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nameCol.setLayoutParams(nameColParams);

        TextView nameView = new TextView(this);
        nameView.setText(displayName);
        nameView.setTextSize(18);
        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
        nameView.setTextColor(colorText);   // ← dopasowany do motywu
        nameCol.addView(nameView);

        TextView emailHint = new TextView(this);
        emailHint.setText(getString(R.string.profile_logged_in));
        emailHint.setTextSize(12);
        emailHint.setTextColor(colorSubtext);
        nameCol.addView(emailHint);
        headerRow.addView(nameCol);

        // Przycisk edycji nazwy
        TextView editBtn = new TextView(this);
        editBtn.setText("✏️");
        editBtn.setTextSize(20);
        editBtn.setPadding(dp8, dp8, dp8, dp8);
        editBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showChangeUsernameDialog();
        });
        headerRow.addView(editBtn);

        container.addView(headerRow);

        // Divider
        View div = new View(this);
        android.widget.LinearLayout.LayoutParams divParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int)(1 * density));
        divParams.bottomMargin = dp16;
        div.setLayoutParams(divParams);
        div.setBackgroundColor(colorDivider());
        container.addView(div);

        // Ulubione
        addProfileMenuItem(container, "❤️", getString(R.string.profile_favorites_label), getString(R.string.profile_favorites_sub), density, () -> {
            dialog.dismiss();
            showFavouritesSheet();
        });

        // Moje pinezki
        addProfileMenuItem(container, "📍", getString(R.string.profile_my_pins_label), getString(R.string.profile_my_pins_sub), density, () -> {
            dialog.dismiss();
            showMyPinsSheet();
        });

        // Divider
        View div2 = new View(this);
        android.widget.LinearLayout.LayoutParams div2Params =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (int)(1 * density));
        div2Params.topMargin = dp8;
        div2Params.bottomMargin = dp8;
        div2.setLayoutParams(div2Params);
        div2.setBackgroundColor(colorDivider());
        container.addView(div2);

        // Wyloguj
        addProfileMenuItem(container, "🚪", getString(R.string.profile_logout), null, density, () -> {
            dialog.dismiss();
            new android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.profile_logout))
                    .setMessage(getString(R.string.profile_logout_confirm))
                    .setPositiveButton(getString(R.string.profile_logout_yes), (d2, w2) -> logout())
                    .setNegativeButton(getString(R.string.pin_delete_cancel), null)
                    .show();
        });

        scroll.addView(container);
        dialog.setContentView(scroll);
        dialog.show();
    }

    private void addProfileMenuItem(android.widget.LinearLayout parent, String emoji,
                                    String title, String subtitle, float density, Runnable onClick) {
        int dp16 = (int)(16 * density), dp8 = (int)(8 * density), dp12 = (int)(12 * density);

        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
        android.widget.LinearLayout.LayoutParams cardParams =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = (int)(8 * density);
        card.setLayoutParams(cardParams);
        card.setRadius(dp16);
        card.setCardElevation(0);
        card.setCardBackgroundColor(colorCard());
        card.setForeground(obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground}).getDrawable(0));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> onClick.run());

        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp16, dp12, dp16, dp12);

        TextView emojiView = new TextView(this);
        emojiView.setText(emoji);
        emojiView.setTextSize(20);
        android.widget.LinearLayout.LayoutParams ep =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        ep.rightMargin = dp16;
        emojiView.setLayoutParams(ep);
        row.addView(emojiView);

        android.widget.LinearLayout textCol = new android.widget.LinearLayout(this);
        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.LinearLayout.LayoutParams tcp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textCol.setLayoutParams(tcp);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(15);
        titleView.setTypeface(null, android.graphics.Typeface.BOLD);
        titleView.setTextColor(colorTextMain());
        textCol.addView(titleView);

        if (subtitle != null) {
            TextView subView = new TextView(this);
            subView.setText(subtitle);
            subView.setTextSize(12);
            subView.setTextColor(colorTextSub());
            textCol.addView(subView);
        }
        row.addView(textCol);

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextSize(20);
        arrow.setTextColor(0xFFCCCCCC);
        row.addView(arrow);

        card.addView(row);
        parent.addView(card);
    }

    // -------------------------------------------------------------------------
    // Zmiana nazwy użytkownika
    // -------------------------------------------------------------------------

    private void showChangeUsernameDialog() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(getString(R.string.profile_username_hint));
        input.setText(sessionUsername);
        input.setSingleLine(true);
        int pad = (int)(16 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad, pad, pad);

        new android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.profile_username_change))
                .setView(input)
                .setPositiveButton(getString(R.string.settings_save), (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, getString(R.string.profile_username_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (newName.length() < 3) {
                        Toast.makeText(this, getString(R.string.profile_username_short), Toast.LENGTH_SHORT).show();
                        return;
                    }
                    updateUsername(newName);
                })
                .setNegativeButton(getString(R.string.pin_delete_cancel), null)
                .show();
    }

    private void updateUsername(String newName) {
        new Thread(() -> {
            try {
                refreshSessionIfNeeded();

                // Aktualizuj w tabeli profiles
                URL url = new URL(SUPABASE_URL + "/rest/v1/profiles?id=eq." + sessionUserId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("PATCH");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                conn.setRequestProperty("Content-Type",  "application/json");
                conn.setRequestProperty("Prefer",        "return=minimal");
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("username", newName);
                conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));
                int code = conn.getResponseCode();
                conn.disconnect();

                if (code == 200 || code == 204) {
                    // Zapisz nową nazwę lokalnie
                    sessionUsername = newName;
                    getSharedPreferences(LoginActivity.PREFS_AUTH, MODE_PRIVATE).edit()
                            .putString(LoginActivity.KEY_USERNAME, newName)
                            .apply();
                    runOnUiThread(() -> {
                        Toast.makeText(this, "✅ Nazwa zmieniona na: " + newName, Toast.LENGTH_SHORT).show();
                        showProfileSheet(); // odśwież sheet
                    });
                } else {
                    runOnUiThread(() ->
                            Toast.makeText(this, "Błąd zmiany nazwy (kod " + code + ")", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.error_connection), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Moje pinezki
    // -------------------------------------------------------------------------

    private void showMyPinsSheet() {
        new Thread(() -> {
            try {
                refreshSessionIfNeeded();
                URL url = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                        + "?author_id=eq." + sessionUserId
                        + "&select=id,name,description,lat,lon,upvotes,downvotes,is_active"
                        + "&order=created_at.desc");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + sessionToken);

                List<MapPoint> myPins = new ArrayList<>();
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray array = new JSONArray(sb.toString());
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        myPins.add(new MapPoint(
                                obj.getDouble("lat"), obj.getDouble("lon"),
                                sanitiseInput(obj.optString("name", "?")),
                                sanitiseInput(obj.optString("description", "")),
                                obj.optString("id", null),
                                sessionUserId,
                                obj.optInt("upvotes", 0),
                                obj.optInt("downvotes", 0)));
                    }
                }
                conn.disconnect();

                final List<MapPoint> finalPins = myPins;
                runOnUiThread(() -> {
                    float density = getResources().getDisplayMetrics().density;
                    int dp16 = (int)(16 * density), dp8 = (int)(8 * density),
                            dp4 = (int)(4 * density), dp2 = (int)(2 * density);

                    BottomSheetDialog dialog = new BottomSheetDialog(this);
                    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                    android.widget.LinearLayout container = new android.widget.LinearLayout(this);
                    container.setOrientation(android.widget.LinearLayout.VERTICAL);
                    container.setBackgroundColor(colorBg());
                    container.setPadding(dp16, dp8, dp16, dp16 * 2);

                    container.addView(buildDragHandle());

                    TextView header = new TextView(this);
                    header.setText(String.format(getString(R.string.profile_my_pins), finalPins.size()));
                    header.setTextSize(18);
                    header.setTypeface(null, android.graphics.Typeface.BOLD);
                    header.setTextColor(colorTextMain());
                    android.widget.LinearLayout.LayoutParams hp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    hp.bottomMargin = dp16;
                    header.setLayoutParams(hp);
                    container.addView(header);

                    if (finalPins.isEmpty()) {
                        TextView empty = new TextView(this);
                        empty.setText(R.string.profile_no_pins);
                        empty.setTextSize(14);
                        empty.setTextColor(colorTextSub());
                        empty.setLineSpacing(0, 1.4f);
                        container.addView(empty);
                    } else {
                        for (int i = 0; i < finalPins.size(); i++) {
                            MapPoint pin = finalPins.get(i);

                            androidx.cardview.widget.CardView card =
                                    new androidx.cardview.widget.CardView(this);
                            android.widget.LinearLayout.LayoutParams cardParams =
                                    new android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                            cardParams.bottomMargin = dp8;
                            card.setLayoutParams(cardParams);
                            card.setRadius(dp16);
                            card.setCardElevation(2 * density);
                            card.setCardBackgroundColor(colorCard());
                            card.setForeground(obtainStyledAttributes(
                                    new int[]{android.R.attr.selectableItemBackground}).getDrawable(0));
                            card.setClickable(true);
                            card.setFocusable(true);
                            card.setOnClickListener(v -> {
                                dialog.dismiss();
                                moveToLocationAndOpen(pin.lat, pin.lon, pin.name, pin.pinId);
                            });

                            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                            row.setPadding(dp16, dp8 + dp4, dp16, dp8 + dp4);

                            // Numer
                            TextView rank = new TextView(this);
                            rank.setText((i + 1) + ".");
                            rank.setTextSize(16);
                            rank.setTypeface(null, android.graphics.Typeface.BOLD);
                            rank.setTextColor(colorTextSub());
                            android.widget.LinearLayout.LayoutParams rankP =
                                    new android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                            rankP.rightMargin = dp8 + dp4;
                            rank.setLayoutParams(rankP);
                            row.addView(rank);

                            // Nazwa + opis
                            android.widget.LinearLayout textCol = new android.widget.LinearLayout(this);
                            textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
                            android.widget.LinearLayout.LayoutParams tcp =
                                    new android.widget.LinearLayout.LayoutParams(0,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                            textCol.setLayoutParams(tcp);

                            TextView nameView = new TextView(this);
                            nameView.setText(pin.name);
                            nameView.setTextSize(15);
                            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
                            nameView.setTextColor(colorTextMain());
                            nameView.setMaxLines(1);
                            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            textCol.addView(nameView);

                            if (!pin.description.isEmpty()) {
                                TextView descView = new TextView(this);
                                descView.setText(pin.description);
                                descView.setTextSize(12);
                                descView.setTextColor(colorTextSub());
                                descView.setMaxLines(1);
                                descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                                textCol.addView(descView);
                            }
                            row.addView(textCol);

                            // Głosy — kolumna ▲ / ▼ jak w Top 10
                            android.widget.LinearLayout voteCol = new android.widget.LinearLayout(this);
                            voteCol.setOrientation(android.widget.LinearLayout.VERTICAL);
                            voteCol.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
                            voteCol.setPadding(dp8, 0, 0, 0);

                            TextView upView = new TextView(this);
                            upView.setText("▲ " + pin.upvotes);
                            upView.setTextSize(13);
                            upView.setTypeface(null, android.graphics.Typeface.BOLD);
                            upView.setTextColor(0xFF4CAF50);
                            upView.setPadding(0, 0, 0, dp2);
                            voteCol.addView(upView);

                            TextView downView = new TextView(this);
                            downView.setText("▼ " + Math.abs(pin.downvotes));
                            downView.setTextSize(13);
                            downView.setTypeface(null, android.graphics.Typeface.BOLD);
                            downView.setTextColor(0xFFFF5252);
                            voteCol.addView(downView);

                            row.addView(voteCol);
                            card.addView(row);
                            container.addView(card);
                        }
                    }

                    scroll.addView(container);
                    dialog.setContentView(scroll);
                    dialog.show();
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.error_my_pins), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Ulubione — bottom sheet
    // -------------------------------------------------------------------------

    private void showFavouritesSheet() {
        new Thread(() -> {
            try {
                refreshSessionIfNeeded();
                URL url = new URL(SUPABASE_URL + "/rest/v1/pin_favourites"
                        + "?user_id=eq." + sessionUserId
                        + "&select=pin_id,pins(id,name,description,lat,lon,upvotes,downvotes)");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + sessionToken);

                List<MapPoint> favPins = new ArrayList<>();
                if (conn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray array = new JSONArray(sb.toString());
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject row = array.getJSONObject(i);
                        JSONObject pin = row.optJSONObject("pins");
                        if (pin != null) {
                            favPins.add(new MapPoint(
                                    pin.getDouble("lat"), pin.getDouble("lon"),
                                    sanitiseInput(pin.optString("name", "?")),
                                    sanitiseInput(pin.optString("description", "")),
                                    pin.optString("id", null), "",
                                    pin.optInt("upvotes", 0), pin.optInt("downvotes", 0)));
                        }
                    }
                }
                conn.disconnect();

                final List<MapPoint> finalPins = favPins;
                runOnUiThread(() -> {
                    float density = getResources().getDisplayMetrics().density;
                    int dp16 = (int)(16 * density), dp8 = (int)(8 * density),
                            dp4 = (int)(4 * density), dp2 = (int)(2 * density);

                    BottomSheetDialog dialog = new BottomSheetDialog(this);
                    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                    scroll.setBackgroundColor(colorBg());
                    android.widget.LinearLayout container = new android.widget.LinearLayout(this);
                    container.setOrientation(android.widget.LinearLayout.VERTICAL);
                    container.setBackgroundColor(colorBg());
                    container.setPadding(dp16, dp8, dp16, dp16 * 2);

                    container.addView(buildDragHandle());

                    TextView header = new TextView(this);
                    header.setText(getString(R.string.profile_favorites));
                    header.setTextSize(18);
                    header.setTypeface(null, android.graphics.Typeface.BOLD);
                    header.setTextColor(colorTextMain());
                    android.widget.LinearLayout.LayoutParams hp =
                            new android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                    hp.bottomMargin = dp16;
                    header.setLayoutParams(hp);
                    container.addView(header);

                    if (finalPins.isEmpty()) {
                        TextView empty = new TextView(this);
                        empty.setText(R.string.profile_no_favorites);
                        empty.setTextSize(14);
                        empty.setTextColor(colorTextSub());
                        empty.setLineSpacing(0, 1.4f);
                        container.addView(empty);
                    } else {
                        for (MapPoint pin : finalPins) {
                            androidx.cardview.widget.CardView card =
                                    new androidx.cardview.widget.CardView(this);
                            android.widget.LinearLayout.LayoutParams cardParams =
                                    new android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                            cardParams.bottomMargin = dp8;
                            card.setLayoutParams(cardParams);
                            card.setRadius(dp16);
                            card.setCardElevation(2 * density);
                            card.setCardBackgroundColor(colorCard());
                            card.setForeground(obtainStyledAttributes(
                                    new int[]{android.R.attr.selectableItemBackground}).getDrawable(0));
                            card.setClickable(true);
                            card.setFocusable(true);
                            card.setOnClickListener(v -> { dialog.dismiss(); moveToLocationAndOpen(pin.lat, pin.lon, pin.name, pin.pinId); });

                            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                            row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                            row.setPadding(dp16, dp8 + dp4, dp16, dp8 + dp4);

                            TextView heart = new TextView(this);
                            heart.setText("❤️");
                            heart.setTextSize(18);
                            android.widget.LinearLayout.LayoutParams hep =
                                    new android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                            hep.rightMargin = dp16;
                            heart.setLayoutParams(hep);
                            row.addView(heart);

                            android.widget.LinearLayout textCol = new android.widget.LinearLayout(this);
                            textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
                            android.widget.LinearLayout.LayoutParams tcp =
                                    new android.widget.LinearLayout.LayoutParams(0,
                                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                            textCol.setLayoutParams(tcp);
                            TextView nameView = new TextView(this);
                            nameView.setText(pin.name);
                            nameView.setTextSize(15);
                            nameView.setTypeface(null, android.graphics.Typeface.BOLD);
                            nameView.setTextColor(colorTextMain());
                            nameView.setMaxLines(1);
                            nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            textCol.addView(nameView);
                            if (!pin.description.isEmpty()) {
                                TextView desc = new TextView(this);
                                desc.setText(pin.description);
                                desc.setTextSize(12);
                                desc.setTextColor(colorTextSub());
                                desc.setMaxLines(1);
                                desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
                                textCol.addView(desc);
                            }
                            row.addView(textCol);

                            // Głosy — kolumna ▲ / ▼ identyczna jak w Moje pinezki i Top 10
                            android.widget.LinearLayout voteCol = new android.widget.LinearLayout(this);
                            voteCol.setOrientation(android.widget.LinearLayout.VERTICAL);
                            voteCol.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
                            voteCol.setPadding(dp8, 0, 0, 0);

                            TextView upView = new TextView(this);
                            upView.setText("▲ " + pin.upvotes);
                            upView.setTextSize(13);
                            upView.setTypeface(null, android.graphics.Typeface.BOLD);
                            upView.setTextColor(0xFF4CAF50);
                            upView.setPadding(0, 0, 0, dp2);
                            voteCol.addView(upView);

                            TextView downView = new TextView(this);
                            downView.setText("▼ " + pin.downvotes);
                            downView.setTextSize(13);
                            downView.setTypeface(null, android.graphics.Typeface.BOLD);
                            downView.setTextColor(0xFFFF5252);
                            voteCol.addView(downView);

                            row.addView(voteCol);
                            card.addView(row);
                            container.addView(card);
                        }
                    }

                    scroll.addView(container);
                    dialog.setContentView(scroll);
                    dialog.show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.error_favorites), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Ustawienia — bottom sheet
    // -------------------------------------------------------------------------


    /** Tworzy drag handle (uchwyt) dla bottom sheet — reaguje na motyw */
    private android.view.View buildDragHandle() {
        float density = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(this);
        wrapper.setOrientation(android.widget.LinearLayout.VERTICAL);
        wrapper.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        int dp20 = (int)(20 * density);

        android.view.View handle = new android.view.View(this);
        android.widget.LinearLayout.LayoutParams lp =
                new android.widget.LinearLayout.LayoutParams(
                        (int)(40 * density), (int)(4 * density));
        lp.bottomMargin = dp20;
        handle.setLayoutParams(lp);

        // colorOnSurfaceVariant z atrybutu motywu (android.R.attr jest zawsze dostępny)
        android.util.TypedValue tv = new android.util.TypedValue();
        int color = 0xFFAAAAAA; // fallback
        if (getTheme().resolveAttribute(android.R.attr.textColorSecondary, tv, true)) {
            color = tv.data;
        }
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius((int)(2 * density));
        handle.setBackground(gd);
        handle.setAlpha(0.3f);

        wrapper.addView(handle);
        return wrapper;
    }
    private void showSettingsSheet() {
        float density = getResources().getDisplayMetrics().density;
        int dp16 = (int)(16 * density), dp8 = (int)(8 * density), dp4 = (int)(4 * density);

        android.content.SharedPreferences prefs =
                getSharedPreferences("app_settings", MODE_PRIVATE);
        final String[] selectedTheme   = {prefs.getString("theme",    "system")};
        final String[] selectedLang    = {prefs.getString("language", "pl")};
        final boolean[] notifEnabled   = {prefs.getBoolean("notifications", true)};
        final boolean[] hasChanges     = {false};

        BottomSheetDialog dialog = new BottomSheetDialog(this);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true); // allows container to fill scroll height for centering
        android.widget.LinearLayout container = new android.widget.LinearLayout(this);
        container.setOrientation(android.widget.LinearLayout.VERTICAL);
        container.setGravity(android.view.Gravity.CENTER_VERTICAL);
        container.setBackgroundColor(colorBg());
        container.setPadding(dp16, dp8, dp16, dp16 * 2);

        container.addView(buildDragHandle());

        // Nagłówek
        TextView header = new TextView(this);
        header.setText(getString(R.string.settings_title));
        header.setTextSize(18);
        header.setTypeface(null, android.graphics.Typeface.BOLD);
        header.setTextColor(colorTextMain());
        android.widget.LinearLayout.LayoutParams hp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        hp.bottomMargin = dp16;
        header.setLayoutParams(hp);
        container.addView(header);

        // --- Motyw ---
        container.addView(buildSectionLabel(getString(R.string.settings_theme), density));
        String[] themeLabels = {getString(R.string.settings_theme_light), getString(R.string.settings_theme_dark), getString(R.string.settings_theme_system)};
        String[] themeValues = {"light", "dark", "system"};
        android.widget.RadioGroup themeGroup = new android.widget.RadioGroup(this);
        themeGroup.setOrientation(android.widget.RadioGroup.VERTICAL);
        themeGroup.setPadding(dp16, dp16, dp16, dp16);
        android.widget.LinearLayout.LayoutParams tgp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);

        themeGroup.setLayoutParams(tgp);
        for (int i = 0; i < themeLabels.length; i++) {
            android.widget.RadioButton rb = buildRadioButton(themeLabels[i], density);
            if (themeValues[i].equals(selectedTheme[0])) rb.setChecked(true);
            final String val = themeValues[i];
            rb.setOnClickListener(v -> { selectedTheme[0] = val; hasChanges[0] = true; });
            themeGroup.addView(rb);
        }
        container.addView(buildSettingsCard(themeGroup, density));

        // --- Powiadomienia ---
        container.addView(buildSectionLabel(getString(R.string.settings_notifications), density));
        androidx.cardview.widget.CardView notifCard = buildSettingsCard(null, density);
        android.widget.LinearLayout notifRow = new android.widget.LinearLayout(this);
        notifRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        notifRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        notifRow.setPadding(dp16, dp16, dp16, dp16);
        TextView notifLabel = new TextView(this);
        notifLabel.setText(getString(R.string.settings_notif_enable));
        notifLabel.setTextSize(14);
        notifLabel.setTextColor(colorTextMain());
        android.widget.LinearLayout.LayoutParams nlp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        notifLabel.setLayoutParams(nlp);
        notifRow.addView(notifLabel);
        android.widget.Switch notifSwitch = new android.widget.Switch(this);
        android.widget.LinearLayout.LayoutParams swp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        swp.gravity = android.view.Gravity.CENTER_VERTICAL;
        notifSwitch.setLayoutParams(swp);
        notifSwitch.setChecked(notifEnabled[0]);
        notifSwitch.setOnCheckedChangeListener((b, checked) -> {
            notifEnabled[0] = checked; hasChanges[0] = true;
        });
        notifRow.addView(notifSwitch);
        notifCard.addView(notifRow);
        container.addView(notifCard);

        // --- Język ---
        container.addView(buildSectionLabel(getString(R.string.settings_language), density));
        String[] langLabels = {"🇵🇱  Polski", "🇬🇧  English"};
        String[] langValues = {"pl", "en"};

        androidx.cardview.widget.CardView langCard = buildSettingsCard(null, density);
        android.widget.LinearLayout langRow = new android.widget.LinearLayout(this);
        langRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        langRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        langRow.setPadding(dp16, dp16, dp16, dp16);

        TextView langLabel = new TextView(this);
        langLabel.setText(getString(R.string.settings_language_label));
        langLabel.setTextSize(14);
        langLabel.setTextColor(colorTextMain());
        android.widget.LinearLayout.LayoutParams llp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        langLabel.setLayoutParams(llp);
        langRow.addView(langLabel);

        android.widget.Spinner langSpinner = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> langAdapter =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, langLabels);
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(langAdapter);

        // Set current selection
        int currentLangIdx = 0;
        for (int i = 0; i < langValues.length; i++) {
            if (langValues[i].equals(selectedLang[0])) { currentLangIdx = i; break; }
        }
        langSpinner.setSelection(currentLangIdx);
        // Listener set after updateSaveBtn is defined below
        langRow.addView(langSpinner);
        langCard.addView(langRow);
        container.addView(langCard);

        // --- Przyciski Zapisz / Reset ---
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(this);
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams brp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        brp.topMargin = dp8;
        btnRow.setLayoutParams(brp);

        int btnRadius = (int)(24 * density);

        android.widget.Button resetBtn = new android.widget.Button(this);
        android.widget.LinearLayout.LayoutParams rbp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rbp.rightMargin = dp8;
        rbp.bottomMargin = dp8;
        resetBtn.setLayoutParams(rbp);
        resetBtn.setText(getString(R.string.settings_reset));
        resetBtn.setTextColor(colorTextSub());
        android.graphics.drawable.GradientDrawable resetBg = new android.graphics.drawable.GradientDrawable();
        resetBg.setColor(colorCard2());
        resetBg.setCornerRadius(btnRadius);
        resetBtn.setBackground(resetBg);
        resetBtn.setAllCaps(false);
        resetBtn.setPadding(dp16, dp8, dp16, dp8);

        android.widget.Button saveBtn = new android.widget.Button(this);
        android.widget.LinearLayout.LayoutParams sbp =
                new android.widget.LinearLayout.LayoutParams(0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        saveBtn.setLayoutParams(sbp);
        saveBtn.setText(getString(R.string.settings_save));
        saveBtn.setTextColor(0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
        saveBg.setColor(0xFFBDBDBD);
        saveBg.setCornerRadius(btnRadius);
        saveBtn.setBackground(saveBg);
        saveBtn.setAllCaps(false);
        saveBtn.setPadding(dp16, dp8, dp16, dp8);

        // Obserwuj zmiany — po każdej aktualizacji odśwież kolor Zapisz
        Runnable updateSaveBtn = () -> {
            saveBg.setColor(hasChanges[0] ? 0xFF4CAF50 : 0xFFBDBDBD);
            saveBtn.setBackground(saveBg);
        };

        themeGroup.setOnCheckedChangeListener((g, id) -> { hasChanges[0] = true; updateSaveBtn.run(); });
        notifSwitch.setOnCheckedChangeListener((b, checked) -> {
            notifEnabled[0] = checked; hasChanges[0] = true; updateSaveBtn.run();
        });
        langSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            boolean firstCall = true; // skip initial programmatic selection
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view,
                                       int position, long id) {
                if (firstCall) { firstCall = false; return; }
                selectedLang[0] = langValues[position];
                hasChanges[0] = true;

                updateSaveBtn.run();
            }
            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        saveBtn.setOnClickListener(v -> {
            if (!hasChanges[0]) return;
            prefs.edit()
                    .putString("theme",         selectedTheme[0])
                    .putString("language",      selectedLang[0])
                    .putBoolean("notifications", notifEnabled[0])
                    .apply();
            applyLanguage(selectedLang[0]);
            applyTheme(selectedTheme[0]);
            hasChanges[0] = false;
            updateSaveBtn.run();

            Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show();
        });

        resetBtn.setOnClickListener(v -> {
            // Cofnij niezapisane zmiany — po prostu zamknij i otwórz sheet od nowa
            // (prefs nie są dotykane, więc wróci do aktualnie zapisanych wartości)
            dialog.dismiss();
            showSettingsSheet();
        });

        btnRow.addView(resetBtn);
        btnRow.addView(saveBtn);
        container.addView(btnRow);

        scroll.addView(container);
        dialog.setContentView(scroll);
        dialog.show();
    }

    private TextView buildSectionLabel(String text, float density) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(colorTextSub());
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setAllCaps(false);
        android.widget.LinearLayout.LayoutParams p =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = (int)(6 * density);
        tv.setLayoutParams(p);
        return tv;
    }

    private androidx.cardview.widget.CardView buildSettingsCard(android.view.View child, float density) {
        int dp16 = (int)(16 * density), dp8 = (int)(8 * density);
        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
        android.widget.LinearLayout.LayoutParams cp =
                new android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = dp16;
        card.setLayoutParams(cp);
        card.setRadius(dp16);
        card.setCardElevation(0);
        card.setCardBackgroundColor(colorCard());
        if (child != null) card.addView(child);
        return card;
    }

    private android.widget.RadioButton buildRadioButton(String text, float density) {
        int dp16 = (int)(16 * density);
        int dp12 = (int)(12 * density);
        android.widget.RadioButton rb = new android.widget.RadioButton(this);
        rb.setText(text);
        rb.setTextSize(14);
        rb.setTextColor(colorTextMain());
        rb.setPadding(dp16, dp12, dp16, dp12);
        rb.setMinHeight((int)(48 * density));
        rb.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return rb;
    }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    /** Call once in onCreate to register the notification channel (required on API 26+). */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            String id   = getString(R.string.notif_channel_id);
            String name = getString(R.string.notif_channel_name);
            String desc = getString(R.string.notif_channel_desc);
            android.app.NotificationChannel channel =
                    new android.app.NotificationChannel(
                            id, name, android.app.NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription(desc);
            android.app.NotificationManager nm =
                    getSystemService(android.app.NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    /** Returns true only if the user hasn't disabled notifications in Settings. */
    private boolean areNotificationsEnabled() {
        return getSharedPreferences("app_settings", MODE_PRIVATE)
                .getBoolean("notifications", true);
    }

    /**
     * Posts a local notification informing the pin author that their pin was upvoted.
     * Respects the in-app notifications toggle and the system notification permission.
     */
    private void sendUpvoteNotification(String pinName) {
        if (!areNotificationsEnabled()) return;

        // On Android 13+ POST_NOTIFICATIONS permission must be granted
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        }

        String title = getString(R.string.notif_upvote_title);
        String body  = getString(R.string.notif_upvote_body, pinName);

        androidx.core.app.NotificationCompat.Builder builder =
                new androidx.core.app.NotificationCompat.Builder(
                        this, getString(R.string.notif_channel_id))
                        .setSmallIcon(android.R.drawable.ic_menu_upload)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true);

        androidx.core.app.NotificationManagerCompat nm =
                androidx.core.app.NotificationManagerCompat.from(this);
        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    /**
     * Wczytuje zapisane ustawienia i stosuje je przy każdym starcie Activity.
     * Musi być wywołana w onCreate() PRZED setContentView().
     */
    private void applyStoredSettings() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("app_settings", MODE_PRIVATE);
        applyTheme(prefs.getString("theme", "system"));
        applyLanguage(prefs.getString("language", "pl"));
    }

    private void applyTheme(String theme) {
        int mode;
        switch (theme) {
            case "light":  mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;       break;
            case "dark":   mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;      break;
            default:       mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; break;
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode);
    }


    private void applyLanguage(String langCode) {
        androidx.core.os.LocaleListCompat locales =
                langCode.equals("system") || langCode.isEmpty()
                        ? androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                        : androidx.core.os.LocaleListCompat.forLanguageTags(langCode);
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales);
    }

    // -------------------------------------------------------------------------
    // Ulubione — toggle serca
    // -------------------------------------------------------------------------

    private void toggleFavourite(String pinId, TextView favBtn) {
        if (!isLoggedIn()) {
            goToLogin();
            return;
        }
        new Thread(() -> {
            try {
                refreshSessionIfNeeded();
                // Sprawdź czy już jest w ulubionych
                URL checkUrl = new URL(SUPABASE_URL + "/rest/v1/pin_favourites"
                        + "?pin_id=eq." + pinId + "&user_id=eq." + sessionUserId + "&select=id&limit=1");
                HttpURLConnection checkConn = (HttpURLConnection) checkUrl.openConnection();
                checkConn.setRequestMethod("GET");
                checkConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                checkConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                boolean isFav = false;
                if (checkConn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(checkConn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    isFav = new org.json.JSONArray(sb.toString()).length() > 0;
                }
                checkConn.disconnect();

                if (isFav) {
                    // Usuń z ulubionych
                    URL delUrl = new URL(SUPABASE_URL + "/rest/v1/pin_favourites"
                            + "?pin_id=eq." + pinId + "&user_id=eq." + sessionUserId);
                    HttpURLConnection delConn = (HttpURLConnection) delUrl.openConnection();
                    delConn.setRequestMethod("DELETE");
                    delConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                    delConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                    delConn.getResponseCode();
                    delConn.disconnect();
                    runOnUiThread(() -> {
                        favBtn.setText("♡");
                        favBtn.setTextColor(0xFFCCCCCC);
                        Toast.makeText(this, "Usunięto z ulubionych", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    // Dodaj do ulubionych
                    URL insUrl = new URL(SUPABASE_URL + "/rest/v1/pin_favourites");
                    HttpURLConnection insConn = (HttpURLConnection) insUrl.openConnection();
                    insConn.setRequestMethod("POST");
                    insConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                    insConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                    insConn.setRequestProperty("Content-Type",  "application/json");
                    insConn.setRequestProperty("Prefer",        "return=minimal");
                    insConn.setDoOutput(true);
                    org.json.JSONObject body = new org.json.JSONObject();
                    body.put("pin_id",  pinId);
                    body.put("user_id", sessionUserId);
                    insConn.getOutputStream().write(body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    insConn.getResponseCode();
                    insConn.disconnect();
                    runOnUiThread(() -> {
                        favBtn.setText("♥");
                        favBtn.setTextColor(0xFFFF5252);
                        Toast.makeText(this, "Dodano do ulubionych ❤️", Toast.LENGTH_SHORT).show();
                    });
                }
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }


    // ─── Dark-mode aware color helpers ───────────────────────────────────────

    private boolean isDarkMode() {
        int flags = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return flags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /** Tło główne: #FFFFFF (light) / #1E1E1E (dark) */
    private int colorBg()       { return isDarkMode() ? 0xFF1E1E1E : 0xFFFFFFFF; }
    /** Tło kart / elementów: #F5F5F5 (light) / #2A2A2A (dark) */
    private int colorCard()     { return isDarkMode() ? 0xFF2A2A2A : 0xFFF5F5F5; }
    /** Tło kart drugorzędnych: #EFEFEF (light) / #2F2F2F (dark) */
    private int colorCard2()    { return isDarkMode() ? 0xFF2F2F2F : 0xFFEFEFEF; }
    /** Tekst główny: #1A1A1A (light) / #EEEEEE (dark) */
    private int colorTextMain() { return isDarkMode() ? 0xFFEEEEEE : 0xFF1A1A1A; }
    /** Tekst drugorzędny: #888888 (light) / #AAAAAA (dark) */
    private int colorTextSub()  { return isDarkMode() ? 0xFFAAAAAA : 0xFF888888; }
    /** Divider: #1F000000 (light) / #33FFFFFF (dark) */
    private int colorDivider()  { return isDarkMode() ? 0x33FFFFFF : 0x1F000000; }
    /** Opis pinezki bg: #F5F5F5 (light) / #242424 (dark) */
    private int colorDescBg()   { return isDarkMode() ? 0xFF242424 : 0xFFF5F5F5; }
    /** Opis pinezki tekst: #66000000 (light) / #BBBBBB (dark) */
    private int colorDescText() { return isDarkMode() ? 0xFFBBBBBB : 0x99000000; }

    // -------------------------------------------------------------------------
    // Top 10 pins bottom sheet
    // -------------------------------------------------------------------------

    private void showTopPinsSheet() {
        new Thread(() -> {
            try {
                URL url = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                        + "?select=id%2Cname%2Cdescription%2Clat%2Clon%2Cupvotes%2Cdownvotes%2Cauthor_id"
                        + "&is_active=eq.true&limit=50");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Content-Type",  "application/json");

                if (conn.getResponseCode() != 200) {
                    runOnUiThread(() -> Toast.makeText(this, getString(R.string.leaderboard_error), Toast.LENGTH_SHORT).show());
                    return;
                }

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                JSONArray array = new JSONArray(sb.toString());
                List<MapPoint> rawPins = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    rawPins.add(new MapPoint(
                            obj.getDouble("lat"),
                            obj.getDouble("lon"),
                            sanitiseInput(obj.optString("name", "?")),
                            sanitiseInput(obj.optString("description", "")),
                            obj.optString("id", null),
                            obj.optString("author_id", ""),
                            obj.optInt("upvotes", 0),
                            obj.optInt("downvotes", 0)
                    ));
                }

                rawPins.sort((a, b) -> {
                    int scoreB = b.upvotes + Math.abs(b.downvotes);
                    int scoreA = a.upvotes + Math.abs(a.downvotes);
                    return scoreB - scoreA;
                });
                final List<MapPoint> topPins = rawPins.size() > 10 ? rawPins.subList(0, 10) : rawPins;

                runOnUiThread(() -> {
                    if (topPins.isEmpty()) {
                        Toast.makeText(this, getString(R.string.leaderboard_empty), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    BottomSheetDialog dialog = new BottomSheetDialog(this);
                    android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                    android.widget.LinearLayout container = new android.widget.LinearLayout(this);
                    container.setOrientation(android.widget.LinearLayout.VERTICAL);
                    float density = getResources().getDisplayMetrics().density;
                    int dp16 = (int)(16 * density);
                    int dp8  = (int)(8  * density);
                    int dp4  = (int)(4  * density);
                    int dp2  = (int)(2  * density);
                    container.setBackgroundColor(colorBg());
                    container.setPadding(dp16, dp8, dp16, dp16);

                    container.addView(buildDragHandle());

                    TextView header = new TextView(this);
                    header.setText(getString(R.string.leaderboard_title));
                    header.setTextSize(18);
                    header.setTypeface(null, android.graphics.Typeface.BOLD);
                    header.setTextColor(colorTextMain());
                    header.setPadding(0, 0, 0, dp16);
                    container.addView(header);

                    for (int i = 0; i < topPins.size(); i++) {
                        MapPoint pin = topPins.get(i);
                        final int idx = i;

                        androidx.cardview.widget.CardView card = new androidx.cardview.widget.CardView(this);
                        androidx.cardview.widget.CardView.LayoutParams cardParams =
                                new androidx.cardview.widget.CardView.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
                        cardParams.setMargins(0, 0, 0, dp8);
                        card.setLayoutParams(cardParams);
                        card.setRadius(dp16);
                        card.setCardElevation(2 * density);
                        card.setCardBackgroundColor(colorCard());

                        android.widget.LinearLayout row = new android.widget.LinearLayout(this);
                        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                        row.setPadding(dp16, dp8 + dp4, dp16, dp8 + dp4);

                        TextView rank = new TextView(this);
                        String medal = idx == 0 ? "🥇" : idx == 1 ? "🥈" : idx == 2 ? "🥉" : (idx + 1) + ".";
                        rank.setText(medal);
                        rank.setTextSize(20);
                        rank.setPadding(0, 0, dp8 + dp4, 0);
                        row.addView(rank);

                        android.widget.LinearLayout textCol = new android.widget.LinearLayout(this);
                        textCol.setOrientation(android.widget.LinearLayout.VERTICAL);
                        android.widget.LinearLayout.LayoutParams textParams =
                                new android.widget.LinearLayout.LayoutParams(0,
                                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                        textCol.setLayoutParams(textParams);

                        TextView nameView = new TextView(this);
                        nameView.setText(pin.name);
                        nameView.setTextSize(15);
                        nameView.setTypeface(null, android.graphics.Typeface.BOLD);
                        nameView.setTextColor(colorTextMain());
                        nameView.setMaxLines(1);
                        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                        textCol.addView(nameView);

                        if (!pin.description.isEmpty()) {
                            TextView descView = new TextView(this);
                            descView.setText(pin.description);
                            descView.setTextSize(12);
                            descView.setTextColor(colorTextSub());
                            descView.setMaxLines(1);
                            descView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                            textCol.addView(descView);
                        }
                        row.addView(textCol);

                        android.widget.LinearLayout voteCol = new android.widget.LinearLayout(this);
                        voteCol.setOrientation(android.widget.LinearLayout.VERTICAL);
                        voteCol.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
                        voteCol.setPadding(dp8, 0, 0, 0);

                        TextView upView = new TextView(this);
                        upView.setText("▲ " + pin.upvotes);
                        upView.setTextSize(13);
                        upView.setTypeface(null, android.graphics.Typeface.BOLD);
                        upView.setTextColor(0xFF4CAF50);
                        upView.setPadding(0, 0, 0, dp2);
                        voteCol.addView(upView);

                        TextView downView = new TextView(this);
                        downView.setText("▼ " + Math.abs(pin.downvotes));
                        downView.setTextSize(13);
                        downView.setTypeface(null, android.graphics.Typeface.BOLD);
                        downView.setTextColor(0xFFFF5252);
                        voteCol.addView(downView);

                        row.addView(voteCol);
                        card.addView(row);

                        final MapPoint p = pin;
                        card.setForeground(obtainStyledAttributes(
                                new int[]{android.R.attr.selectableItemBackground}).getDrawable(0));
                        card.setClickable(true);
                        card.setFocusable(true);
                        card.setOnClickListener(v -> {
                            dialog.dismiss();
                            moveToLocationAndOpen(p.lat, p.lon, p.name, p.pinId);
                        });

                        container.addView(card);
                    }

                    scroll.addView(container);
                    dialog.setContentView(scroll);
                    dialog.show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.leaderboard_error), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Search — Nominatim
    // -------------------------------------------------------------------------

    static class SearchResult {
        final double lat, lon;
        final String displayName, shortName;
        SearchResult(double lat, double lon, String displayName) {
            this.lat = lat; this.lon = lon; this.displayName = displayName;
            int comma = displayName.indexOf(',');
            this.shortName = comma > 0 ? displayName.substring(0, comma).trim() : displayName;
        }
    }

    interface SearchSuccessCallback { void onResults(List<SearchResult> results); }
    interface SearchEmptyCallback   { void onEmpty(); }

    private void performSearch(String query,
                               SearchSuccessCallback onSuccess,
                               SearchEmptyCallback onEmpty) {
        new Thread(() -> {
            try {
                String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                URL url = new URL("https://nominatim.openstreetmap.org/search?q="
                        + encoded + "&format=json&limit=5&addressdetails=0");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", getPackageName());
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                JSONArray array = new JSONArray(sb.toString());
                if (array.length() == 0) {
                    runOnUiThread(() -> { if (onEmpty != null) onEmpty.onEmpty();
                    else Toast.makeText(this, "Nie znaleziono miejsca", Toast.LENGTH_SHORT).show(); });
                    return;
                }
                List<SearchResult> results = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    results.add(new SearchResult(obj.getDouble("lat"), obj.getDouble("lon"),
                            obj.getString("display_name")));
                }
                runOnUiThread(() -> {
                    if (onSuccess != null) onSuccess.onResults(results);
                    else { SearchResult f = results.get(0); moveToLocation(f.lat, f.lon, f.shortName); }
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> Toast.makeText(this, getString(R.string.search_error), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // RecyclerView adapter
    // -------------------------------------------------------------------------

    interface OnResultClick { void onClick(SearchResult item); }

    static class SearchResultAdapter extends RecyclerView.Adapter<SearchResultAdapter.VH> {
        private final List<SearchResult> items;
        private final OnResultClick listener;
        SearchResultAdapter(List<SearchResult> items, OnResultClick listener) {
            this.items = items; this.listener = listener;
        }
        static class VH extends RecyclerView.ViewHolder {
            final TextView name, address;
            VH(View v) { super(v); name = v.findViewById(R.id.resultName); address = v.findViewById(R.id.resultAddress); }
        }
        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new VH(android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.search_result_item, parent, false));
        }
        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            SearchResult item = items.get(pos);
            h.name.setText(item.shortName);
            h.address.setText(item.displayName);
            h.itemView.setOnClickListener(v -> listener.onClick(item));
        }
        @Override public int getItemCount() { return items.size(); }
    }

    // -------------------------------------------------------------------------
    // Map / location
    // -------------------------------------------------------------------------

    private void moveToLocation(double lat, double lon, String name) {
        GeoPoint point = new GeoPoint(lat, lon);
        map.getController().setZoom(19.0);
        map.getController().animateTo(point);
        saveLastLocation(lat, lon, name);
        // Ustaw centrum wyszukiwania pinezek na wyszukane miejsce
        searchCenterLat = lat;
        searchCenterLon = lon;
        showNearbyPoints(lat, lon);
        // NIE przesuwamy markera GPS — zostaje na prawdziwej pozycji użytkownika
        map.invalidate();
    }

    @SuppressWarnings("MissingPermission")
    private void tryShowCachedLocation() {
        if (!hasLocationPermission()) return;
        fusedLocationClient.getLastLocation().addOnSuccessListener(this, loc -> {
            if (loc != null) {
                double lat = loc.getLatitude();
                double lon = loc.getLongitude();
                updateUserLocationMarker(lat, lon);
                searchCenterLat = lat;
                searchCenterLon = lon;
                map.getController().animateTo(new GeoPoint(lat, lon), 20.0, 500L);
                showNearbyPoints(lat, lon);
            } else {
                Toast.makeText(this, "Brak sygnału GPS — spróbuj na zewnątrz", Toast.LENGTH_SHORT).show();
            }
        });
    }
    private void updateUserLocationMarker(double lat, double lon) {
        if (userLocationMarker != null) {
            userLocationMarker.setPosition(new GeoPoint(lat, lon));
            map.invalidate();
            return;
        }

        userLocationMarker = new Marker(map);
        userLocationMarker.setPosition(new GeoPoint(lat, lon));
        userLocationMarker.setAnchor(0.5f, 0.5f);
        userLocationMarker.setInfoWindow(null);
        userLocationMarker.setTitle("Twoja pozycja");

        // Wczytaj PNG z przezroczystym tłem
        Drawable icon = ContextCompat.getDrawable(this, R.drawable.aktualna);
        if (icon != null) {
            int size = (int)(47 * getResources().getDisplayMetrics().density);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            icon.setBounds(0, 0, size, size);
            icon.draw(canvas);
            userLocationMarker.setIcon(new BitmapDrawable(getResources(), bmp));
        }

        userLocationMarker.setOnMarkerClickListener((m, mv) -> true);
        map.getOverlays().add(0, userLocationMarker);
        map.invalidate();
    }

    /**
     * Przesuwa mapę do pinezki i otwiera jej szczegółowy sheet.
     * Jeśli marker nie jest jeszcze załadowany (np. poza zasięgiem widoku),
     * czeka chwilę na renderowanie mapy i próbuje ponownie.
     */
    private void moveToLocationAndOpen(double lat, double lon, String name, String pinId) {
        moveToLocation(lat, lon, name);
        if (pinId == null || pinId.isEmpty()) return;
        // Marker może być wczytywany asynchronicznie — dajemy mapie chwilę
        map.postDelayed(() -> {
            Marker target = null;
            for (org.osmdroid.views.overlay.Overlay overlay : map.getOverlays()) {
                if (overlay instanceof Marker) {
                    Marker m = (Marker) overlay;
                    if (pinId.equals(m.getRelatedObject())) {
                        target = m;
                        break;
                    }
                }
            }
            if (target != null) {
                showPinDetailSheet(target);
            }
        }, 800);
    }

    private void addMarker(GeoPoint point, String title, String description) {
        addMarkerWithId(point, title, description, null, "", 0, 0);
    }

    private void addMarkerWithId(GeoPoint point, String title, String description,
                                 String pinId, String authorId, int upvotes, int downvotes) {
        Marker marker = new Marker(map);
        marker.setPosition(point);
        marker.setTitle((title != null ? title : "Pinezka") + "  ▲" + upvotes + " ▼" + downvotes);
        marker.setSnippet(upvotes + "|" + downvotes + "|" + (authorId != null ? authorId : ""));

        if (description != null && !description.isEmpty()) marker.setSubDescription(description);

        marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        marker.setInfoWindow(null);
        if (pinId != null) marker.setRelatedObject(pinId);

        Drawable icon = ContextCompat.getDrawable(this, R.drawable.voteit_pinicon);

        if (icon != null) {

            int targetHeight = 120;


            float aspectRatio = (float) icon.getIntrinsicWidth() / (float) icon.getIntrinsicHeight();
            int targetWidth = Math.round(targetHeight * aspectRatio);

            // 3. Tworzysz bitmapę z automatycznie dobraną szerokością
            Bitmap bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
            icon.draw(canvas);

            marker.setIcon(new BitmapDrawable(getResources(), bitmap));
        }


        marker.setOnMarkerClickListener((m, mv) -> { showPinDetailSheet(m); return true; });
        map.getOverlays().add(marker);
        map.invalidate();
    }

    private void showNearbyPoints(double userLat, double userLon) {
        // Usuń tylko pinezki POI — NIE usuwaj markera lokalizacji użytkownika
        for (int i = map.getOverlays().size() - 1; i >= 0; i--) {
            org.osmdroid.views.overlay.Overlay o = map.getOverlays().get(i);
            if (o instanceof Marker && o != userLocationMarker)
                map.getOverlays().remove(i);
        }

        List<MapPoint> points = new ArrayList<>();
        points.addAll(loadUserPoints());
        for (MapPoint p : points)
            if (distance(userLat, userLon, p.lat, p.lon) <= 50)
                addMarkerWithId(new GeoPoint(p.lat, p.lon), p.name, p.description,
                        p.pinId, p.authorId, p.upvotes, p.downvotes);
        map.invalidate();
    }

    // -------------------------------------------------------------------------
    // Supabase — sync pinezek
    // -------------------------------------------------------------------------

    private void syncPointsFromSupabase() {
        new Thread(() -> {
            try {
                URL url = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                        + "?select=id%2Clat%2Clon%2Cname%2Cdescription%2Cupvotes%2Cdownvotes%2Cauthor_id&is_active=eq.true");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Content-Type",  "application/json");

                if (conn.getResponseCode() != 200) return;

                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line; while ((line = r.readLine()) != null) sb.append(line);
                }
                conn.disconnect();

                JSONArray array = new JSONArray(sb.toString());

                File file = new File(getFilesDir(), "points_user.txt");
                try (FileWriter writer = new FileWriter(file, false)) {
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject obj = array.getJSONObject(i);
                        double lat      = obj.getDouble("lat");
                        double lon      = obj.getDouble("lon");
                        String name     = sanitiseInput(obj.optString("name", ""));
                        String desc     = sanitiseInput(obj.optString("description", ""));
                        String pinId    = obj.optString("id", "");
                        String authorId = obj.optString("author_id", "");
                        int    upvotes   = obj.optInt("upvotes",   0);
                        int    downvotes = obj.optInt("downvotes", 0);

                        writer.append(String.valueOf(lat)).append(DELIMITER)
                                .append(String.valueOf(lon)).append(DELIMITER)
                                .append(name).append(DELIMITER)
                                .append(desc).append(DELIMITER)
                                .append(pinId).append(DELIMITER)
                                .append(authorId).append(DELIMITER)
                                .append(String.valueOf(upvotes)).append(DELIMITER)
                                .append(String.valueOf(downvotes)).append("\n");
                    }
                }

                // Zbierz dane z JSON do mapy pinId → MapPoint
                java.util.Map<String, MapPoint> dbPins = new java.util.LinkedHashMap<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject obj = array.getJSONObject(i);
                    String pid = obj.optString("id", "");
                    if (pid.isEmpty()) continue;
                    dbPins.put(pid, new MapPoint(
                            obj.getDouble("lat"), obj.getDouble("lon"),
                            sanitiseInput(obj.optString("name", "")),
                            sanitiseInput(obj.optString("description", "")),
                            pid,
                            obj.optString("author_id", ""),
                            obj.optInt("upvotes",   0),
                            obj.optInt("downvotes", 0)
                    ));
                }

                // Użyj centrum wyszukiwania (GPS lub wyszukane miasto)
                final double centerLat = searchCenterLat != 0 ? searchCenterLat
                        : (loadLastLocation() != null ? loadLastLocation().getLatitude() : 0);
                final double centerLon = searchCenterLon != 0 ? searchCenterLon
                        : (loadLastLocation() != null ? loadLastLocation().getLongitude() : 0);
                if (centerLat == 0 && centerLon == 0) return;
                final java.util.Map<String, MapPoint> finalDbPins = dbPins;

                runOnUiThread(() -> {
                    // Zbierz istniejące markery na mapie (pinId → Marker)
                    java.util.Map<String, Marker> existingMarkers = new java.util.LinkedHashMap<>();
                    for (org.osmdroid.views.overlay.Overlay o : map.getOverlays()) {
                        if (o instanceof Marker) {
                            Marker m = (Marker) o;
                            Object rel = m.getRelatedObject();
                            if (rel instanceof String) existingMarkers.put((String) rel, m);
                        }
                    }

                    boolean changed = false;

                    // 1. Usuń markery których nie ma już w bazie (is_active=false lub deleted)
                    for (java.util.Map.Entry<String, Marker> e : existingMarkers.entrySet()) {
                        if (!finalDbPins.containsKey(e.getKey())) {
                            map.getOverlays().remove(e.getValue());
                            changed = true;
                        }
                    }

                    // 2. Dodaj nowe / zaktualizuj istniejące — BEZ usuwania i rysowania od nowa
                    for (java.util.Map.Entry<String, MapPoint> e : finalDbPins.entrySet()) {
                        MapPoint p = e.getValue();
                        if (distance(centerLat, centerLon, p.lat, p.lon) > 50) continue;

                        Marker existing = existingMarkers.get(p.pinId);
                        if (existing != null) {
                            // Zaktualizuj tylko snippet i tytuł (głosy) — marker nie znika
                            String oldSnippet = existing.getSnippet();
                            String authorPart = "";
                            if (oldSnippet != null) {
                                String[] sp = oldSnippet.split("\\|", 3);
                                authorPart = sp.length > 2 ? sp[2] : (sp.length > 1 ? sp[1] : "");
                                // jeśli stary format bez downvotes
                                if (sp.length == 2) authorPart = sp[1];
                            }
                            String newSnippet = p.upvotes + "|" + p.downvotes + "|" + p.authorId;
                            if (!newSnippet.equals(oldSnippet)) {
                                existing.setSnippet(newSnippet);
                                String base = existing.getTitle();
                                if (base != null && base.contains("  "))
                                    base = base.substring(0, base.lastIndexOf("  "));
                                existing.setTitle((base != null ? base : p.name)
                                        + "  ▲" + p.upvotes + " ▼" + p.downvotes);
                                changed = true;
                            }
                        } else {
                            // Nowy marker — dodaj
                            addMarkerWithId(new GeoPoint(p.lat, p.lon), p.name, p.description,
                                    p.pinId, p.authorId, p.upvotes, p.downvotes);
                            changed = true;
                        }
                    }

                    if (changed) map.invalidate();
                });

            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Supabase — dodaj pinezkę
    // -------------------------------------------------------------------------

    private void uploadPointToSupabase(double lat, double lon, String name, String desc) {
        String safeName = sanitiseInput(name);
        String safeDesc = sanitiseInput(desc);
        if (safeName.isEmpty()) return;

        new Thread(() -> {
            try {
                refreshSessionIfNeeded();

                URL url = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                conn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                conn.setRequestProperty("Content-Type",  "application/json");
                conn.setRequestProperty("Prefer",        "return=representation");
                conn.setDoOutput(true);

                JSONObject body = new JSONObject();
                body.put("lat",         lat);
                body.put("lon",         lon);
                body.put("name",        safeName);
                body.put("description", safeDesc);
                body.put("author_id",   sessionUserId);

                conn.getOutputStream().write(body.toString().getBytes(StandardCharsets.UTF_8));

                int code = conn.getResponseCode();
                if (code == 201) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray arr = new JSONArray(sb.toString());
                    if (arr.length() > 0) {
                        String pinId = arr.getJSONObject(0).optString("id", "");
                        savePointToFile(lat, lon, safeName, safeDesc, pinId, 0, 0);
                    }
                    syncPointsFromSupabase();
                }
                conn.disconnect();

            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Supabase — głosowanie (NAPRAWIONE: brak race condition)
    //
    // Strategia: aplikacja tylko INSERT/PATCH swój wiersz w pin_votes.
    // Trigger update_pin_upvotes() w Supabase atomowo przelicza sumy i zapisuje
    // je do pins.upvotes / pins.downvotes — bez wyścigu między klientami.
    // Po oddaniu głosu aplikacja odczytuje gotowy wynik z tabeli pins.
    // -------------------------------------------------------------------------

    /** Wrapper bez aktualizacji UI (nieużywany, zachowany dla zgodności) */
    private void castVote(String pinId, int value, boolean deleteVote) {
        castVoteWithView(pinId, value, null, null);
    }

    /**
     * Oddaje lub zmienia głos na pinezkę.
     * 1. INSERT lub PATCH wiersza w pin_votes.
     * 2. Krótkie opóźnienie (150 ms) na propagację triggera.
     * 3. GET pins → odczyt zaktualizowanych upvotes/downvotes.
     * 4. Aktualizacja UI.
     *
     * Trigger update_pin_upvotes() wykonuje zliczanie atomowo po stronie bazy —
     * aplikacja nigdy nie nadpisuje liczników samodzielnie.
     */
    private void castVoteWithView(String pinId, int value, TextView upCountView, TextView downCountView) {
        if (!isLoggedIn()) return;

        new Thread(() -> {
            try {
                refreshSessionIfNeeded();

                // Krok 1: sprawdź czy głos już istnieje
                URL checkUrl = new URL(SUPABASE_URL + "/rest/v1/pin_votes"
                        + "?pin_id=eq." + pinId
                        + "&user_id=eq." + sessionUserId
                        + "&select=id&limit=1");
                HttpURLConnection checkConn = (HttpURLConnection) checkUrl.openConnection();
                checkConn.setRequestMethod("GET");
                checkConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                checkConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                boolean voteExists = false;
                if (checkConn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(checkConn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    voteExists = new JSONArray(sb.toString()).length() > 0;
                }
                checkConn.disconnect();

                int resultCode;
                if (voteExists) {
                    // PATCH istniejącego głosu → trigger zaktualizuje pins atomowo
                    URL patchUrl = new URL(SUPABASE_URL + "/rest/v1/pin_votes"
                            + "?pin_id=eq." + pinId
                            + "&user_id=eq." + sessionUserId);
                    HttpURLConnection patchConn = (HttpURLConnection) patchUrl.openConnection();
                    patchConn.setRequestMethod("PATCH");
                    patchConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                    patchConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                    patchConn.setRequestProperty("Content-Type",  "application/json");
                    patchConn.setRequestProperty("Prefer",        "return=minimal");
                    patchConn.setDoOutput(true);
                    JSONObject patchBody = new JSONObject();
                    patchBody.put("value", value);
                    patchConn.getOutputStream().write(patchBody.toString().getBytes(StandardCharsets.UTF_8));
                    resultCode = patchConn.getResponseCode();
                    patchConn.disconnect();
                } else {
                    // INSERT nowego głosu → trigger zaktualizuje pins atomowo
                    URL insertUrl = new URL(SUPABASE_URL + "/rest/v1/pin_votes");
                    HttpURLConnection insertConn = (HttpURLConnection) insertUrl.openConnection();
                    insertConn.setRequestMethod("POST");
                    insertConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                    insertConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                    insertConn.setRequestProperty("Content-Type",  "application/json");
                    insertConn.setRequestProperty("Prefer",        "return=minimal");
                    insertConn.setDoOutput(true);
                    JSONObject insertBody = new JSONObject();
                    insertBody.put("pin_id",  pinId);
                    insertBody.put("user_id", sessionUserId);
                    insertBody.put("value",   value);
                    insertConn.getOutputStream().write(insertBody.toString().getBytes(StandardCharsets.UTF_8));
                    resultCode = insertConn.getResponseCode();
                    insertConn.disconnect();
                }

                if (resultCode != 200 && resultCode != 201 && resultCode != 204) {
                    android.util.Log.e("VoteIt", "castVote failed code=" + resultCode);
                    return;
                }

                // Krok 2: poczekaj na trigger, potem odczytaj wynik z pins
                Thread.sleep(150);

                URL pinUrl = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                        + "?id=eq." + pinId + "&select=upvotes,downvotes&limit=1");
                HttpURLConnection pinConn = (HttpURLConnection) pinUrl.openConnection();
                pinConn.setRequestMethod("GET");
                pinConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                pinConn.setRequestProperty("Authorization", "Bearer " + sessionToken);

                int finalUpvotes = 0, finalDownvotes = 0;
                if (pinConn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(pinConn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray arr = new JSONArray(sb.toString());
                    if (arr.length() > 0) {
                        finalUpvotes   = arr.getJSONObject(0).optInt("upvotes",   0);
                        finalDownvotes = arr.getJSONObject(0).optInt("downvotes", 0);
                    }
                }
                pinConn.disconnect();

                // Krok 3: zaktualizuj UI + wyślij powiadomienie dla autora pinezki przy upvote
                final int fu = finalUpvotes, fd = finalDownvotes;

                // Fetch pin author + name to decide whether to send notification
                String notifAuthorId = "";
                String notifPinTitle = "";
                if (value == 1) { // only bother on upvote
                    try {
                        URL au = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                                + "?id=eq." + pinId + "&select=author_id,name&limit=1");
                        HttpURLConnection ac = (HttpURLConnection) au.openConnection();
                        ac.setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY);
                        ac.setRequestProperty("Authorization", "Bearer " + sessionToken);
                        if (ac.getResponseCode() == 200) {
                            StringBuilder sb2 = new StringBuilder();
                            try (BufferedReader r2 = new BufferedReader(
                                    new InputStreamReader(ac.getInputStream()))) {
                                String l; while ((l = r2.readLine()) != null) sb2.append(l);
                            }
                            JSONArray a2 = new JSONArray(sb2.toString());
                            if (a2.length() > 0) {
                                notifAuthorId = a2.getJSONObject(0).optString("author_id", "");
                                notifPinTitle = a2.getJSONObject(0).optString("name", "");
                            }
                        }
                        ac.disconnect();
                    } catch (Exception ignored) {}
                }
                final String finalNotifAuthor = notifAuthorId;
                final String finalNotifTitle  = notifPinTitle;

                runOnUiThread(() -> {
                    if (upCountView   != null) upCountView.setText(String.valueOf(fu));
                    if (downCountView != null) downCountView.setText(String.valueOf(fd));
                    updateMarkerCounts(pinId, fu, fd);
                    // Send local notification if current user is the pin author and notifications on
                    if (value == 1
                            && !finalNotifAuthor.isEmpty()
                            && finalNotifAuthor.equals(sessionUserId)
                            && areNotificationsEnabled()) {
                        sendUpvoteNotification(finalNotifTitle);
                    }
                });

            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    /**
     * Usuwa głos użytkownika (DELETE z pin_votes).
     * Trigger przelicza sumy po stronie bazy — aplikacja tylko odczytuje wynik.
     */
    private void removeVote(String pinId, TextView upCountView, TextView downCountView) {
        if (!isLoggedIn()) return;
        new Thread(() -> {
            try {
                refreshSessionIfNeeded();

                // DELETE głosu → trigger zaktualizuje pins atomowo
                URL delUrl = new URL(SUPABASE_URL + "/rest/v1/pin_votes"
                        + "?pin_id=eq." + pinId
                        + "&user_id=eq." + sessionUserId);
                HttpURLConnection delConn = (HttpURLConnection) delUrl.openConnection();
                delConn.setRequestMethod("DELETE");
                delConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                delConn.setRequestProperty("Authorization", "Bearer " + sessionToken);
                delConn.getResponseCode();
                delConn.disconnect();

                // Poczekaj na trigger, potem odczytaj wynik z pins
                Thread.sleep(150);

                URL pinUrl = new URL(SUPABASE_URL + "/rest/v1/" + SUPABASE_TABLE
                        + "?id=eq." + pinId + "&select=upvotes,downvotes&limit=1");
                HttpURLConnection pinConn = (HttpURLConnection) pinUrl.openConnection();
                pinConn.setRequestMethod("GET");
                pinConn.setRequestProperty("apikey",        BuildConfig.SUPABASE_ANON_KEY);
                pinConn.setRequestProperty("Authorization", "Bearer " + sessionToken);

                int newUp = 0, newDown = 0;
                if (pinConn.getResponseCode() == 200) {
                    StringBuilder sb = new StringBuilder();
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(pinConn.getInputStream()))) {
                        String line; while ((line = r.readLine()) != null) sb.append(line);
                    }
                    JSONArray arr = new JSONArray(sb.toString());
                    if (arr.length() > 0) {
                        newUp   = arr.getJSONObject(0).optInt("upvotes",   0);
                        newDown = arr.getJSONObject(0).optInt("downvotes", 0);
                    }
                }
                pinConn.disconnect();

                final int fu = newUp, fd = newDown;
                runOnUiThread(() -> {
                    if (upCountView   != null) upCountView.setText(String.valueOf(fu));
                    if (downCountView != null) downCountView.setText(String.valueOf(fd));
                    updateMarkerCounts(pinId, fu, fd);
                });

            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    /**
     * Aktualizuje snippet i tytuł markera na mapie po zmianie głosów.
     * Musi być wywołana z UI thread.
     */
    private void updateMarkerCounts(String pinId, int upvotes, int downvotes) {
        for (org.osmdroid.views.overlay.Overlay o : map.getOverlays()) {
            if (o instanceof Marker) {
                Marker m = (Marker) o;
                if (pinId.equals(m.getRelatedObject())) {
                    String oldSnippet = m.getSnippet();
                    String authorPart = "";
                    if (oldSnippet != null) {
                        String[] sp = oldSnippet.split("\\|", 3);
                        authorPart = sp.length > 2 ? sp[2] : "";
                    }
                    m.setSnippet(upvotes + "|" + downvotes + "|" + authorPart);
                    String baseName = m.getTitle();
                    if (baseName != null && baseName.contains("  "))
                        baseName = baseName.substring(0, baseName.lastIndexOf("  "));
                    m.setTitle((baseName != null ? baseName : "Pinezka")
                            + "  ▲" + upvotes + " ▼" + downvotes);
                    break;
                }
            }
        }
        map.invalidate();
    }

    // -------------------------------------------------------------------------
    // Sanitize
    // -------------------------------------------------------------------------

    private String sanitiseInput(String raw) {
        if (raw == null) return "";
        String s = raw.trim().replaceAll("[\\x00-\\x1F\\x7F]", "").replaceAll("[\t\n\r]", "");
        return s.length() > 200 ? s.substring(0, 200).trim() : s;
    }

    // -------------------------------------------------------------------------
    // Persistence (local file)
    // -------------------------------------------------------------------------

    private void saveLastLocation(double lat, double lon, String name) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
                .putFloat(KEY_LAT, (float) lat).putFloat(KEY_LON, (float) lon)
                .putString(KEY_NAME, name).apply();
    }

    private GeoPoint loadLastLocation() {
        var prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (!prefs.contains(KEY_LAT)) return null;
        return new GeoPoint((double) prefs.getFloat(KEY_LAT, 0f),
                (double) prefs.getFloat(KEY_LON, 0f));
    }

    private void savePointToFile(double lat, double lon, String name, String desc, String pinId, int upvotes, int downvotes) {
        try {
            File file = new File(getFilesDir(), "points_user.txt");
            try (FileWriter w = new FileWriter(file, true)) {
                w.append(String.valueOf(lat)).append(DELIMITER)
                        .append(String.valueOf(lon)).append(DELIMITER)
                        .append(name).append(DELIMITER)
                        .append(desc).append(DELIMITER)
                        .append(pinId).append(DELIMITER)
                        .append(sessionUserId).append(DELIMITER)
                        .append(String.valueOf(upvotes)).append(DELIMITER)
                        .append(String.valueOf(downvotes)).append("\n");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private List<MapPoint> loadUserPoints() {
        List<MapPoint> pts = new ArrayList<>();
        try {
            File file = new File(getFilesDir(), "points_user.txt");
            if (!file.exists()) return pts;
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split(DELIMITER, 8);
                    if (p.length < 2) continue;
                    try {
                        double lat      = Double.parseDouble(p[0].trim());
                        double lon      = Double.parseDouble(p[1].trim());
                        String name     = p.length > 2 ? p[2] : "";
                        String desc     = p.length > 3 ? p[3] : "";
                        String pid      = p.length > 4 ? p[4] : null;
                        String authorId = p.length > 5 ? p[5] : "";
                        int upvotes     = p.length > 6 ? Integer.parseInt(p[6].trim()) : 0;
                        int downvotes   = p.length > 7 ? Integer.parseInt(p[7].trim()) : 0;
                        pts.add(new MapPoint(lat, lon, name, desc, pid, authorId, upvotes, downvotes));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return pts;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private double distance(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // -------------------------------------------------------------------------
    // Inner classes
    // -------------------------------------------------------------------------

    static class MapPoint {
        final double lat, lon;
        final String name, description;
        final String pinId;
        final String authorId;
        final int upvotes;
        final int downvotes;
        MapPoint(double lat, double lon, String name, String description, String pinId, String authorId, int upvotes, int downvotes) {
            this.lat = lat; this.lon = lon;
            this.name = name; this.description = description;
            this.pinId = pinId;
            this.authorId = authorId != null ? authorId : "";
            this.upvotes = upvotes;
            this.downvotes = downvotes;
        }
    }
}