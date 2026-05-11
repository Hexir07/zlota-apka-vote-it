package com.voteitapp.voteit;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * LoginActivity — ekran logowania i rejestracji przez Supabase Auth.
 *
 * Dwie zakładki: "Zaloguj" / "Zarejestruj".
 * Po pomyślnym zalogowaniu zapisuje access_token i user_id w SharedPreferences
 * i startuje MainActivity.
 *
 * Supabase Auth REST endpoints używane:
 *   POST /auth/v1/signup   → rejestracja
 *   POST /auth/v1/token?grant_type=password → logowanie
 */
public class LoginActivity extends AppCompatActivity {

    // -----------------------------------------------------------------------
    // Stałe
    // -----------------------------------------------------------------------

    /** Nazwa SharedPreferences przechowujących sesję */
    public static final String PREFS_AUTH         = "auth_prefs";
    public static final String KEY_TOKEN          = "access_token";
    public static final String KEY_REFRESH_TOKEN  = "refresh_token";
    public static final String KEY_USER_ID        = "user_id";
    public static final String KEY_USERNAME       = "username";

    private static final String SUPABASE_URL     = BuildConfig.SUPABASE_URL;
    private static final String SUPABASE_ANON_KEY = BuildConfig.SUPABASE_ANON_KEY;

    // -----------------------------------------------------------------------
    // UI
    // -----------------------------------------------------------------------

    private EditText etEmail, etPassword, etUsername;
    private Button   btnAction, btnSwitch;
    private TextView tvTitle, tvSubtitle, tvUsernameLabel;
    private View     containerUsername;

    private boolean isLoginMode = true;
    /** true gdy LoginActivity otwarto z MainActivity (nie przy zimnym starcie) */
    private boolean canGoBack   = false;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Zastosuj motyw i język przed setContentView
        applyStoredSettings();

        // Sprawdź czy możemy wrócić (otwarto z mapy przez "Zaloguj się")
        canGoBack = getIntent().getBooleanExtra("can_go_back", false);

        // Pomiń ekran logowania tylko przy zimnym starcie gdy token już jest
        // Jeśli can_go_back=true, użytkownik świadomie otworzył ekran logowania
        if (!canGoBack && isLoggedIn()) {
            startMain();
            return;
        }

        setContentView(R.layout.activity_login);

        tvTitle        = findViewById(R.id.loginTitle);
        tvSubtitle     = findViewById(R.id.loginSubtitle);
        tvUsernameLabel = findViewById(R.id.usernameLabel);
        etEmail        = findViewById(R.id.etEmail);
        etPassword     = findViewById(R.id.etPassword);
        etUsername     = findViewById(R.id.etUsername);
        containerUsername = findViewById(R.id.containerUsername);
        btnAction      = findViewById(R.id.btnAction);
        btnSwitch      = findViewById(R.id.btnSwitch);

        renderMode();

        // Przycisk cofnij — widoczny tylko gdy przyszliśmy z mapy
        Button btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) {
            if (canGoBack) {
                btnBack.setVisibility(View.VISIBLE);
                btnBack.setOnClickListener(v -> finish());
            } else {
                btnBack.setVisibility(View.GONE);
            }
        }

        btnAction.setOnClickListener(v -> {
            String email    = etEmail.getText().toString().trim();
            String password = etPassword.getText().toString().trim();
            String username = etUsername.getText().toString().trim();

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, getString(R.string.login_fill_fields), Toast.LENGTH_SHORT).show();
                return;
            }
            if (!isLoginMode && username.isEmpty()) {
                Toast.makeText(this, getString(R.string.login_fill_username), Toast.LENGTH_SHORT).show();
                return;
            }
            if (password.length() < 6) {
                Toast.makeText(this, "Hasło musi mieć min. 6 znaków", Toast.LENGTH_SHORT).show();
                return;
            }

            btnAction.setEnabled(false);
            btnAction.setText(getString(R.string.login_waiting));

            if (isLoginMode) {
                doLogin(email, password);
            } else {
                doRegister(email, password, username);
            }
        });

        btnSwitch.setOnClickListener(v -> {
            isLoginMode = !isLoginMode;
            renderMode();
        });
    }

    // -----------------------------------------------------------------------
    // UI helpers
    // -----------------------------------------------------------------------

    private void renderMode() {
        if (isLoginMode) {
            tvTitle.setText(getString(R.string.login_title_login));
            tvSubtitle.setText(getString(R.string.login_subtitle_login));
            tvUsernameLabel.setVisibility(View.GONE);
            containerUsername.setVisibility(View.GONE);
            btnAction.setText(getString(R.string.login_btn_login_short));
            btnSwitch.setText(getString(R.string.login_switch_to_register));
        } else {
            tvTitle.setText(getString(R.string.login_title_register));
            tvSubtitle.setText(getString(R.string.login_subtitle_register));
            tvUsernameLabel.setVisibility(View.VISIBLE);
            containerUsername.setVisibility(View.VISIBLE);
            btnAction.setText(getString(R.string.login_btn_register_short));
            btnSwitch.setText(getString(R.string.login_switch_to_login));
        }
    }

    private void resetButton() {
        runOnUiThread(() -> {
            btnAction.setEnabled(true);
            btnAction.setText(isLoginMode ? getString(R.string.login_btn_login_short) : getString(R.string.login_btn_register_short));
        });
    }

    // -----------------------------------------------------------------------
    // Supabase Auth — logowanie
    // -----------------------------------------------------------------------

    /**
     * POST /auth/v1/token?grant_type=password
     * Zwraca JSON z access_token i user.id
     */
    private void doLogin(String email, String password) {
        new Thread(() -> {
            try {
                URL url = new URL(SUPABASE_URL + "/auth/v1/token?grant_type=password");
                HttpURLConnection conn = openAuthConnection(url, "POST");

                JSONObject body = new JSONObject();
                body.put("email",    email);
                body.put("password", password);
                sendBody(conn, body);

                int code = conn.getResponseCode();
                String response = readResponse(conn, code);

                if (code == 200) {
                    JSONObject json  = new JSONObject(response);
                    String token        = json.getString("access_token");
                    String refreshToken = json.optString("refresh_token", "");
                    String userId    = json.getJSONObject("user").getString("id");

                    // Pobierz aktualną nazwę z tabeli profiles (user_metadata może być przestarzałe)
                    String username = "";
                    try {
                        URL profileUrl = new URL(SUPABASE_URL
                                + "/rest/v1/profiles?id=eq." + userId + "&select=username&limit=1");
                        HttpURLConnection pc = (HttpURLConnection) profileUrl.openConnection();
                        pc.setRequestMethod("GET");
                        pc.setRequestProperty("apikey", SUPABASE_ANON_KEY);
                        pc.setRequestProperty("Authorization", "Bearer " + token);
                        pc.setConnectTimeout(10_000);
                        pc.setReadTimeout(10_000);
                        String pc_resp = readResponse(pc, pc.getResponseCode());
                        org.json.JSONArray arr = new org.json.JSONArray(pc_resp);
                        if (arr.length() > 0)
                            username = arr.getJSONObject(0).optString("username", "");
                    } catch (Exception ignored) {
                        // fallback do user_metadata gdy zapytanie do profiles się nie powiedzie
                        JSONObject meta = json.getJSONObject("user").optJSONObject("user_metadata");
                        if (meta != null) username = meta.optString("username", "");
                    }

                    saveSession(token, refreshToken, userId, username);
                    runOnUiThread(this::startMain);
                } else {
                    String msg = parseErrorMessage(response);
                    runOnUiThread(() ->
                            Toast.makeText(this, "Błąd: " + msg, Toast.LENGTH_LONG).show());
                    resetButton();
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(this, "Błąd połączenia", Toast.LENGTH_SHORT).show());
                resetButton();
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // Supabase Auth — rejestracja
    // -----------------------------------------------------------------------

    /**
     * POST /auth/v1/signup
     * Przekazuje username w user_metadata — trigger w bazie wczyta to przy tworzeniu profilu.
     */
    private void doRegister(String email, String password, String username) {
        new Thread(() -> {
            try {
                URL url = new URL(SUPABASE_URL + "/auth/v1/signup");
                HttpURLConnection conn = openAuthConnection(url, "POST");

                JSONObject meta = new JSONObject();
                meta.put("username", username);

                JSONObject body = new JSONObject();
                body.put("email",         email);
                body.put("password",      password);
                body.put("data",          meta);   // → user_metadata w auth.users
                sendBody(conn, body);

                int code     = conn.getResponseCode();
                String response = readResponse(conn, code);

                if (code == 200 || code == 201) {
                    JSONObject json = new JSONObject(response);

                    // Supabase może zwrócić pusty access_token gdy email confirmation jest włączone
                    String token  = json.optString("access_token", "");
                    String userId = json.optString("id", "");
                    if (userId.isEmpty() && json.has("user")) {
                        userId = json.getJSONObject("user").optString("id", "");
                    }

                    if (token.isEmpty()) {
                        runOnUiThread(() -> {
                            Toast.makeText(this,
                                    "Sprawdź email i potwierdź konto, a następnie zaloguj się",
                                    Toast.LENGTH_LONG).show();
                            isLoginMode = true;
                            renderMode();
                            resetButton();
                        });
                    } else {
                        String refreshToken = json.optString("refresh_token", "");
                        saveSession(token, refreshToken, userId, username);
                        runOnUiThread(this::startMain);
                    }
                } else {
                    String msg = parseErrorMessage(response);
                    runOnUiThread(() ->
                            Toast.makeText(this, "Błąd rejestracji: " + msg, Toast.LENGTH_LONG).show());
                    resetButton();
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(this, "Błąd połączenia", Toast.LENGTH_SHORT).show());
                resetButton();
            }
        }).start();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private HttpURLConnection openAuthConnection(URL url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("apikey",       SUPABASE_ANON_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        return conn;
    }

    private void sendBody(HttpURLConnection conn, JSONObject body) throws Exception {
        byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
        conn.getOutputStream().write(data);
    }

    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 400 ? conn.getErrorStream() : conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String parseErrorMessage(String jsonResponse) {
        try {
            JSONObject obj = new JSONObject(jsonResponse);
            if (obj.has("msg"))     return obj.getString("msg");
            if (obj.has("message")) return obj.getString("message");
            if (obj.has("error_description")) return obj.getString("error_description");
        } catch (Exception ignored) {}
        return "Nieznany błąd";
    }

    private void saveSession(String token, String refreshToken, String userId, String username) {
        getSharedPreferences(PREFS_AUTH, MODE_PRIVATE).edit()
                .putString(KEY_TOKEN,         token)
                .putString(KEY_REFRESH_TOKEN, refreshToken)
                .putString(KEY_USER_ID,       userId)
                .putString(KEY_USERNAME,      username)
                .apply();
    }

    private boolean isLoggedIn() {
        String token = getSharedPreferences(PREFS_AUTH, MODE_PRIVATE)
                .getString(KEY_TOKEN, "");
        return !token.isEmpty();
    }

    /** Startuje MainActivity i zamyka ekran logowania */
    private void startMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
    private void applyStoredSettings() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("app_settings", MODE_PRIVATE);
        applyTheme(prefs.getString("theme", "system"));
        applyLanguage(prefs.getString("language", "pl"));
    }

    private void applyTheme(String theme) {
        int mode;
        switch (theme) {
            case "light":  mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO;            break;
            case "dark":   mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES;           break;
            default:       mode = androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM; break;
        }
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode);
    }

    private void applyLanguage(String langCode) {
        androidx.core.os.LocaleListCompat locales =
                (langCode.equals("system") || langCode.isEmpty())
                        ? androidx.core.os.LocaleListCompat.getEmptyLocaleList()
                        : androidx.core.os.LocaleListCompat.forLanguageTags(langCode);
        androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(locales);
    }

}