package net.kdt.pojavlaunch.authenticator.yggdrasil;

import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Minimal Yggdrasil (authlib-injector compatible) client.
 *
 * Supports the endpoints every public skin site exposes:
 *   POST {root}/authserver/authenticate
 *   POST {root}/authserver/refresh
 *   GET  {root}/          -> API metadata (used for -Dauthlibinjector.yggdrasil.prefetched)
 *
 * Deliberately dependency free: HttpURLConnection + org.json, no OkHttp.
 */
public class YggdrasilClient {
    private static final int TIMEOUT_MS = 20000;

    public static class Profile {
        public String id;
        public String name;
    }

    public static class AuthResult {
        public String accessToken;
        public String clientToken;
        public Profile selectedProfile;
    }

    public static class YggdrasilException extends Exception {
        public final String error;
        public final String errorMessage;
        public final int httpCode;

        public YggdrasilException(int httpCode, String error, String errorMessage) {
            super((error == null ? "" : error) + ": " + (errorMessage == null ? "" : errorMessage));
            this.httpCode = httpCode;
            this.error = error;
            this.errorMessage = errorMessage;
        }
    }

    public static String normalizeRoot(String apiRoot) {
        if (apiRoot == null) return null;
        String root = apiRoot.trim();
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        return root;
    }

    public static AuthResult authenticate(String apiRoot, String username, String password,
                                          String clientToken)
            throws IOException, JSONException, YggdrasilException {
        String root = normalizeRoot(apiRoot);
        if (root == null || root.isEmpty()) throw new YggdrasilException(0, "InvalidApiRoot", "Empty API root");
        if (clientToken == null || clientToken.isEmpty()) clientToken = UUID.randomUUID().toString();

        JSONObject agent = new JSONObject();
        agent.put("name", "Minecraft");
        agent.put("version", 1);

        JSONObject body = new JSONObject();
        body.put("agent", agent);
        body.put("username", username);
        body.put("password", password);
        body.put("clientToken", clientToken);
        body.put("requestUser", false);

        JSONObject resp = postJson(root + "/authserver/authenticate", body);
        AuthResult result = new AuthResult();
        result.accessToken = resp.optString("accessToken", null);
        result.clientToken = resp.optString("clientToken", clientToken);
        JSONObject profile = resp.optJSONObject("selectedProfile");
        if (profile == null) {
            // Some servers omit selectedProfile when the account owns several characters.
            org.json.JSONArray available = resp.optJSONArray("availableProfiles");
            if (available != null && available.length() > 0) profile = available.optJSONObject(0);
        }
        if (profile != null) {
            result.selectedProfile = new Profile();
            result.selectedProfile.id = profile.optString("id", null);
            result.selectedProfile.name = profile.optString("name", null);
        }
        if (result.accessToken == null || result.accessToken.isEmpty()) {
            throw new YggdrasilException(200, "MalformedResponse", "Server returned no accessToken");
        }
        return result;
    }

    public static AuthResult refresh(String apiRoot, String accessToken, String clientToken)
            throws IOException, JSONException, YggdrasilException {
        String root = normalizeRoot(apiRoot);
        JSONObject body = new JSONObject();
        body.put("accessToken", accessToken);
        body.put("clientToken", clientToken);
        body.put("requestUser", false);
        JSONObject resp = postJson(root + "/authserver/refresh", body);
        AuthResult result = new AuthResult();
        result.accessToken = resp.optString("accessToken", accessToken);
        result.clientToken = resp.optString("clientToken", clientToken);
        JSONObject profile = resp.optJSONObject("selectedProfile");
        if (profile != null) {
            result.selectedProfile = new Profile();
            result.selectedProfile.id = profile.optString("id", null);
            result.selectedProfile.name = profile.optString("name", null);
        }
        return result;
    }

    /**
     * Downloads the API metadata document and returns it Base64 encoded, ready to be passed as
     * -Dauthlibinjector.yggdrasil.prefetched. Caching it means the game does not have to reach
     * the auth server itself when it starts (Android DNS inside the JVM is flaky).
     */
    public static String fetchMetadataPrefetched(String apiRoot) throws IOException, YggdrasilException {
        String root = normalizeRoot(apiRoot);
        HttpURLConnection conn = (HttpURLConnection) new URL(root + "/").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Amethyst-Android");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new YggdrasilException(code, "MetadataFetchFailed", "HTTP " + code);
            String raw = readAll(conn.getInputStream());
            // authlib-injector expects the raw document, base64 encoded
            return Base64.encodeToString(raw.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        } finally {
            conn.disconnect();
        }
    }

    private static JSONObject postJson(String url, JSONObject body)
            throws IOException, JSONException, YggdrasilException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "Amethyst-Android");
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String raw = stream == null ? "" : readAll(stream);
            if (code < 200 || code >= 300) {
                String error = null, message = null;
                try {
                    JSONObject err = new JSONObject(raw);
                    error = err.optString("error", null);
                    message = err.optString("errorMessage", null);
                } catch (JSONException ignored) { /* non-JSON error body */ }
                throw new YggdrasilException(code, error, message == null ? raw : message);
            }
            return new JSONObject(raw);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream src = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = src.read(buf)) != -1) out.write(buf, 0, len);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
