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
    // Railway dodeli PORT; lokalno 8080
    private static final int PORT = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
    // Public URL – koristi se u verifikacionom linku
    static final String APP_URL = System.getenv().getOrDefault("APP_URL", "http://localhost:" + PORT);
    // SQLite baza
    static final String DB = "expenses.db";

    public static void main(String[] args) throws Exception {
        initDb();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        // API
        server.createContext("/api/register", WebServer::apiRegister);
        server.createContext("/api/verify", WebServer::apiVerify);
        server.createContext("/api/login", WebServer::apiLogin);

        // Statika iz /web
        server.createContext("/", WebServer::staticFiles);

        server.setExecutor(null);
        System.out.println("Server running on " + APP_URL);
        server.start();
    }

    /* ========================= DB INIT ========================= */
    static void initDb() throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB);
                Statement s = c.createStatement()) {
            s.execute("""
                        CREATE TABLE IF NOT EXISTS users(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          email TEXT UNIQUE NOT NULL,
                          full_name TEXT,
                          salt TEXT NOT NULL,
                          hash TEXT NOT NULL,
                          verified INTEGER NOT NULL DEFAULT 0,
                          verify_token TEXT,
                          created_at TEXT
                        );
                    """);
        }
    }

    /* ========================= HELPERS ========================= */
    static String read(InputStream in) throws IOException {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            return br.lines().collect(Collectors.joining("\n"));
        }
    }

    static Map<String, String> parseJson(InputStream in) throws IOException {
        String body = read(in);
        if (body == null || body.isBlank())
            return new HashMap<>();
        return GSON.fromJson(body, new TypeToken<Map<String, String>>() {
        }.getType());
    }

    static void json(HttpExchange ex, int code, Object payload) throws IOException {
        byte[] out = (payload instanceof String)
                ? ((String) payload).getBytes("UTF-8")
                : GSON.toJson(payload).getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    static void text(HttpExchange ex, int code, String payload) throws IOException {
        byte[] out = payload.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    /* ========================= AUTH API ========================= */

    // POST /api/register { email, password, full_name }
    static void apiRegister(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB)) {
            Map<String, String> j = parseJson(ex.getRequestBody());
            String email = j.getOrDefault("email", "").trim().toLowerCase();
            String pass = j.getOrDefault("password", "").trim();
            String name = j.getOrDefault("full_name", "").trim();

            if (email.isBlank() || pass.isBlank()) {
                json(ex, 400, Map.of("error", "Nedostaju podaci"));
                return;
            }

            // postoji?
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE email=?")) {
                ps.setString(1, email);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    json(ex, 409, Map.of("error", "Email postoji"));
                    return;
                }
            }

            String[] hp = PasswordUtil.makeHash(pass);
            String token = PasswordUtil.randomToken(24);

            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO users(email,full_name,salt,hash,verified,verify_token,created_at) VALUES(?,?,?,?,0,?,?)")) {
                ins.setString(1, email);
                ins.setString(2, name);
                ins.setString(3, hp[0]);
                ins.setString(4, hp[1]);
                ins.setString(5, token);
                ins.setString(6, Instant.now().toString());
                ins.executeUpdate();
            }

            // verifikacioni link
            String verifyUrl = APP_URL + "/api/verify?token=" + URLEncoder.encode(token, "UTF-8");
            String html = "<p>Klikni da potvrdiš nalog:</p><p><a href='" + verifyUrl + "'>"
                    + verifyUrl + "</a></p>";

            // KLJUČNO: ne blokiraj UI – šalji u pozadini; ako ne uspe, link je u logu
            Mailer.sendHtmlAsync(email, "Verifikacija naloga", html);

            json(ex, 200, Map.of("ok", true));
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, Map.of("error", "Server"));
        }
    }

    // GET /api/verify?token=...
    static void apiVerify(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        String q = ex.getRequestURI().getQuery();
        String token = null;
        if (q != null) {
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0 && kv.substring(0, i).equals("token")) {
                    token = URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                    break;
                }
            }
        }
        if (token == null || token.isBlank()) {
            text(ex, 400, "Missing token");
            return;
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,email,verified FROM users WHERE verify_token=?")) {
                ps.setString(1, token);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    text(ex, 404, "Nevažeći token");
                    return;
                }
                if (rs.getInt("verified") == 1) {
                    redirect(ex, "/login.html?verified=1");
                    return;
                }
                long id = rs.getLong("id");
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE users SET verified=1, verify_token=NULL WHERE id=?")) {
                    up.setLong(1, id);
                    up.executeUpdate();
                }
            }
            redirect(ex, "/login.html?verified=1");
        } catch (SQLException e) {
            e.printStackTrace();
            text(ex, 500, "DB error");
        }
    }

    // POST /api/login { email, password }
    static void apiLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + DB)) {
            Map<String, String> j = parseJson(ex.getRequestBody());
            String email = j.getOrDefault("email", "").trim().toLowerCase();
            String pass = j.getOrDefault("password", "").trim();

            if (email.isBlank() || pass.isBlank()) {
                json(ex, 400, Map.of("error", "Nedostaju podaci"));
                return;
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT salt,hash,verified FROM users WHERE email=?")) {
                ps.setString(1, email);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()) {
                    json(ex, 401, Map.of("error", "Pogrešan email ili lozinka"));
                    return;
                }

                if (rs.getInt("verified") == 0) {
                    json(ex, 403, Map.of("error", "Potvrdi email pre prijave"));
                    return;
                }

                String salt = rs.getString("salt");
                String hash = rs.getString("hash");
                if (!PasswordUtil.verify(pass, salt, hash)) {
                    json(ex, 401, Map.of("error", "Pogrešan email ili lozinka"));
                    return;
                }
            }

            // Ako koristiš sesije/kolacice – ovde ih postavi; za sada samo OK:
            json(ex, 200, Map.of("ok", true));
        } catch (SQLException e) {
            e.printStackTrace();
            json(ex, 500, Map.of("error", "Server"));
        }
    }

    /* ========================= STATIC FILES ========================= */
    static void staticFiles(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();

        // mapiranje root-a na /web/index.html
        if (path.equals("/"))
            path = "/index.html";

        Path file = Paths.get("web" + path).normalize();
        if (!file.startsWith(Paths.get("web")) || !Files.exists(file) || Files.isDirectory(file)) {
            // fallback: /web/index.html za SPA rute
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

    static String guessContentType(Path p) {
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

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }
}
