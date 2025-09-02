import com.sun.net.httpserver.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

public class WebServer {
    private static final Gson GSON = new Gson();

    // PORT (Railway zada kroz env), lokalno 8080
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    // Public URL (koristi se samo za log/health)
    private static final String APP_URL = System.getenv().getOrDefault("APP_URL", "http://localhost:" + PORT);
    // SQLite fajl
    private static final String DB = "expenses.db";

    public static void main(String[] args) throws Exception {
        initDb();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Auth API (bez mejla)
        server.createContext("/api/register", WebServer::apiRegister);
        server.createContext("/api/login", WebServer::apiLogin);
        server.createContext("/api/health", WebServer::apiHealth);

        // Statički fajlovi iz /web
        server.createContext("/", WebServer::staticFiles);

        server.setExecutor(null);
        System.out.println("Server running on " + APP_URL);
        server.start();
    }

    /* ========================= DB ========================= */

    private static void initDb() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB);
                Statement s = c.createStatement()) {
            // Jednostavna tabela naloga bazirana na username + hash + salt
            s.execute("""
                        CREATE TABLE IF NOT EXISTS accounts(
                          id         INTEGER PRIMARY KEY AUTOINCREMENT,
                          username   TEXT UNIQUE NOT NULL,
                          salt       TEXT NOT NULL,
                          hash       TEXT NOT NULL,
                          created_at TEXT
                        );
                    """);
        }
    }

    /* ========================= Helpers ========================= */

    private static String readBody(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    private static Map<String, String> parseJson(InputStream in) throws IOException {
        String body = readBody(in);
        if (body == null || body.isBlank())
            return new HashMap<>();
        return GSON.fromJson(body, new TypeToken<Map<String, String>>() {
        }.getType());
    }

    private static void json(HttpExchange ex, int code, Object payload) throws IOException {
        byte[] out = (payload instanceof String)
                ? ((String) payload).getBytes("UTF-8")
                : GSON.toJson(payload).getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    private static void text(HttpExchange ex, int code, String payload) throws IOException {
        byte[] out = payload.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /* ========================= API ========================= */

    // POST /api/register { username, password }
    private static void apiRegister(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB)) {
            Map<String, String> j = parseJson(ex.getRequestBody());
            String username = j.getOrDefault("username", "").trim().toLowerCase();
            String password = j.getOrDefault("password", "").trim();

            if (username.isBlank() || password.isBlank()) {
                json(ex, 400, Map.of("error", "Nedostaju podaci"));
                return;
            }
            // dozvoli slova, brojeve, tacku i donju crtu; 3-30 char
            if (!username.matches("^[a-z0-9._]{3,30}$")) {
                json(ex, 400, Map.of("error", "Neispravan username"));
                return;
            }

            // postoji?
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM accounts WHERE username=?")) {
                ps.setString(1, username);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    // predlog alternativnog username-a
                    String suggestion = suggestUsername(c, username);
                    json(ex, 409, Map.of("error", "Username zauzet", "suggestion", suggestion));
                    return;
                }
            }

            String[] hp = PasswordUtil.makeHash(password);
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO accounts(username,salt,hash,created_at) VALUES(?,?,?,?)")) {
                ins.setString(1, username);
                ins.setString(2, hp[0]);
                ins.setString(3, hp[1]);
                ins.setString(4, Instant.now().toString());
                ins.executeUpdate();
            }
            json(ex, 200, Map.of("ok", true));
        } catch (SQLException e) {
            e.printStackTrace();
            json(ex, 500, Map.of("error", "DB greška"));
        }
    }

    // POST /api/login { username, password }
    private static void apiLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB)) {
            Map<String, String> j = parseJson(ex.getRequestBody());
            String username = j.getOrDefault("username", "").trim().toLowerCase();
            String password = j.getOrDefault("password", "").trim();

            if (username.isBlank() || password.isBlank()) {
                json(ex, 400, Map.of("error", "Nedostaju podaci"));
                return;
            }

            String salt = null, hash = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT salt,hash FROM accounts WHERE username=?")) {
                ps.setString(1, username);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    salt = rs.getString("salt");
                    hash = rs.getString("hash");
                }
            }
            if (salt == null) {
                json(ex, 401, Map.of("error", "Pogrešan username ili lozinka"));
                return;
            }
            if (!PasswordUtil.verify(password, salt, hash)) {
                json(ex, 401, Map.of("error", "Pogrešan username ili lozinka"));
                return;
            }

            // ovde bi postavljao sesiju/kolacice; za sada samo OK
            json(ex, 200, Map.of("ok", true));
        } catch (SQLException e) {
            e.printStackTrace();
            json(ex, 500, Map.of("error", "DB greška"));
        }
    }

    // GET /api/health
    private static void apiHealth(HttpExchange ex) throws IOException {
        json(ex, 200, Map.of("ok", true, "url", APP_URL));
    }

    private static String suggestUsername(Connection c, String base) throws SQLException {
        // pokušaj base1..base999
        for (int i = 1; i < 1000; i++) {
            String cand = base + i;
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM accounts WHERE username=?")) {
                ps.setString(1, cand);
                ResultSet rs = ps.executeQuery();
                if (!rs.next())
                    return cand;
            }
        }
        return base + "_" + System.currentTimeMillis() / 1000;
    }

    /* ========================= Static files ========================= */

    private static void staticFiles(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/"))
            path = "/index.html";

        Path file = Paths.get("web" + path).normalize();
        if (!file.startsWith(Paths.get("web")) || !Files.exists(file) || Files.isDirectory(file)) {
            file = Paths.get("web/index.html");
            if (!Files.exists(file)) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
        }

        String ctype = guessContentType(file);
        byte[] bytes = Files.readAllBytes(file);
        ex.getResponseHeaders().set("Content-Type", ctype);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String guessContentType(Path p) {
        String n = p.getFileName().toString().toLowerCase();
        if (n.endsWith(".html"))
            return "text/html; charset=UTF-8";
        if (n.endsWith(".css"))
            return "text/css; charset=UTF-8";
        if (n.endsWith(".js"))
            return "application/javascript; charset=UTF-8";
        if (n.endsWith(".png"))
            return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
            return "image/jpeg";
        if (n.endsWith(".svg"))
            return "image/svg+xml";
        return "application/octet-stream";
    }
}
