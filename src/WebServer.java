import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class WebServer {
    static final int PORT = 8080;
    static final String DB = "expenses.db";
    static final Gson gson = new GsonBuilder().create();

    record Expense(long id, long user_id, Long share_id, String name, String category, double amount, String date) {
    }

    record Subscription(long id, long user_id, String service, double amount, int period_days, String next_due,
            boolean active) {
    }

    static Connection conn;
    static Mailer mailer = new Mailer();

    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        conn = DriverManager.getConnection("jdbc:sqlite:" + DB);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");

            st.execute("""
                        CREATE TABLE IF NOT EXISTS users(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          email TEXT UNIQUE NOT NULL,
                          pass_salt TEXT NOT NULL,
                          pass_hash TEXT NOT NULL,
                          full_name TEXT,
                          avatar_url TEXT,
                          verified INTEGER DEFAULT 0,
                          created_at TEXT DEFAULT (datetime('now'))
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS sessions(
                          sid TEXT PRIMARY KEY,
                          user_id INTEGER NOT NULL,
                          created_at TEXT DEFAULT (datetime('now')),
                          expires_at TEXT
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS verify_tokens(
                          token TEXT PRIMARY KEY,
                          user_id INTEGER NOT NULL,
                          created_at TEXT DEFAULT (datetime('now'))
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS expenses(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          user_id INTEGER NOT NULL,
                          share_id INTEGER,
                          name TEXT NOT NULL,
                          category TEXT NOT NULL,
                          amount REAL NOT NULL,
                          date TEXT NOT NULL
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS friend_requests(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          from_user INTEGER NOT NULL,
                          to_user INTEGER NOT NULL,
                          status TEXT NOT NULL DEFAULT 'PENDING',
                          created_at TEXT DEFAULT (datetime('now'))
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS friends(
                          user_id INTEGER NOT NULL,
                          friend_id INTEGER NOT NULL,
                          UNIQUE(user_id, friend_id)
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS expense_shares(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          owner_id INTEGER NOT NULL,
                          label TEXT,
                          token TEXT UNIQUE,
                          created_at TEXT DEFAULT (datetime('now'))
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS expense_share_members(
                          share_id INTEGER NOT NULL,
                          user_id INTEGER NOT NULL,
                          UNIQUE(share_id, user_id)
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS messages(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          share_id INTEGER NOT NULL,
                          from_user INTEGER NOT NULL,
                          body TEXT NOT NULL,
                          sent_at TEXT DEFAULT (datetime('now'))
                        )
                    """);
            st.execute("""
                        CREATE TABLE IF NOT EXISTS subscriptions(
                          id INTEGER PRIMARY KEY AUTOINCREMENT,
                          user_id INTEGER NOT NULL,
                          service TEXT NOT NULL,
                          amount REAL NOT NULL,
                          period_days INTEGER NOT NULL,
                          next_due TEXT NOT NULL,
                          active INTEGER NOT NULL DEFAULT 1,
                          last_notified TEXT
                        )
                    """);
        }

        new Thread(WebServer::reminderLoop, "reminder-loop").start();

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", ex -> {
            try {
                if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
                    cors(ex);
                    send(ex, 200, new byte[0], "text/plain");
                    return;
                }
                String sid = cookie(ex, "sid");
                Long uid = (sid != null) ? sessionUser(sid) : null;
                String path = ex.getRequestURI().getPath();
                if ("/".equals(path)) {
                    serveFile(ex, Paths.get("web").resolve(uid == null ? "login.html" : "index.html"));
                    return;
                }
                Path file = Paths.get("web").resolve(path.substring(1)).normalize();
                if (!file.startsWith(Paths.get("web")) || !Files.exists(file) || Files.isDirectory(file)) {
                    sendText(ex, 404, "Not found");
                    return;
                }
                serveFile(ex, file);
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        // AUTH
        server.createContext("/api/register", ex -> {
            try {
                cors(ex);
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendText(ex, 405, "");
                    return;
                }
                JsonObject j = bodyJson(ex);
                String email = j.get("email").getAsString().trim().toLowerCase(Locale.ROOT);
                String password = j.get("password").getAsString();
                String full = j.has("full_name") && !j.get("full_name").isJsonNull() ? j.get("full_name").getAsString()
                        : null;

                String[] hp = PasswordUtil.makeHash(password);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users(email,pass_salt,pass_hash,full_name,verified) VALUES(?,?,?,?,0)")) {
                    ps.setString(1, email);
                    ps.setString(2, hp[0]);
                    ps.setString(3, hp[1]);
                    ps.setString(4, full);
                    ps.executeUpdate();
                } catch (SQLException se) {
                    sendJson(ex, 400, Map.of("error", "Email postoji"));
                    return;
                }
                long uid = scalarLong("SELECT id FROM users WHERE email=?", email);

                String token = PasswordUtil.randomToken(24);
                try (PreparedStatement ps = conn
                        .prepareStatement("INSERT INTO verify_tokens(token,user_id) VALUES(?,?)")) {
                    ps.setString(1, token);
                    ps.setLong(2, uid);
                    ps.executeUpdate();
                }
                String link = origin(ex) + "/api/verify?token=" + token;
                mailer.send(email, "Verifikacija naloga",
                        "<p>Klikni da potvrdiš nalog:</p><p><a href='" + link + "'>" + link + "</a></p>");

                sendJson(ex, 201, Map.of("status", "ok", "info", "Proveri email za verifikaciju"));
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/verify", ex -> {
            try {
                cors(ex);
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendText(ex, 405, "");
                    return;
                }
                String token = q(ex, "token");
                if (token == null) {
                    sendText(ex, 400, "Bad token");
                    return;
                }
                Long uid = scalarLongOrNull("SELECT user_id FROM verify_tokens WHERE token=?", token);
                if (uid == null) {
                    sendText(ex, 400, "Invalid token");
                    return;
                }
                try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET verified=1 WHERE id=?")) {
                    ps.setLong(1, uid);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM verify_tokens WHERE token=?")) {
                    ps.setString(1, token);
                    ps.executeUpdate();
                }
                ex.getResponseHeaders().add("Location", "/login.html?verified=1");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/login", ex -> {
            try {
                cors(ex);
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendText(ex, 405, "");
                    return;
                }
                JsonObject j = bodyJson(ex);
                String email = j.get("email").getAsString().trim().toLowerCase(Locale.ROOT);
                String password = j.get("password").getAsString();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id,pass_salt,pass_hash,verified,full_name,avatar_url FROM users WHERE email=?")) {
                    ps.setString(1, email);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            sendJson(ex, 401, Map.of("error", "Nalog ne postoji"));
                            return;
                        }
                        long uid = rs.getLong(1);
                        String salt = rs.getString(2), hash = rs.getString(3);
                        boolean verified = rs.getInt(4) == 1;
                        String full = rs.getString(5), avatar = rs.getString(6);
                        if (!PasswordUtil.verify(password, salt, hash)) {
                            sendJson(ex, 401, Map.of("error", "Pogrešna lozinka"));
                            return;
                        }
                        if (!verified) {
                            sendJson(ex, 403, Map.of("error", "Verifikuj email pre prijave"));
                            return;
                        }
                        String sid = PasswordUtil.randomToken(24);
                        String exp = OffsetDateTime.now().plusDays(30).toString();
                        try (PreparedStatement ps2 = conn
                                .prepareStatement("INSERT INTO sessions(sid,user_id,expires_at) VALUES(?,?,?)")) {
                            ps2.setString(1, sid);
                            ps2.setLong(2, uid);
                            ps2.setString(3, exp);
                            ps2.executeUpdate();
                        }
                        cookieOut(ex, "sid", sid, 30);
                        sendJson(ex, 200, Map.of("user",
                                Map.of("id", uid, "email", email, "full_name", full, "avatar_url", avatar)));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/logout", ex -> {
            try {
                cors(ex);
                String sid = cookie(ex, "sid");
                if (sid != null)
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM sessions WHERE sid=?")) {
                        ps.setString(1, sid);
                        ps.executeUpdate();
                    } catch (Exception ignore) {
                    }
                cookieOut(ex, "sid", "", 0);
                sendJson(ex, 200, Map.of("ok", true));
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/me", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;
                try (PreparedStatement ps = conn
                        .prepareStatement("SELECT email,full_name,avatar_url,verified FROM users WHERE id=?")) {
                    ps.setLong(1, uid);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            sendText(ex, 404, "");
                            return;
                        }
                        sendJson(ex, 200, Map.of("id", uid, "email", rs.getString(1), "full_name", rs.getString(2),
                                "avatar_url", rs.getString(3), "verified", rs.getInt(4) == 1));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        // EXPENSES
        server.createContext("/api/expenses", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;

                if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    String month = q(ex, "month");
                    ArrayList<Expense> list = new ArrayList<>();
                    String sql = "SELECT id,user_id,share_id,name,category,amount,date FROM expenses WHERE user_id=? ";
                    List<Object> params = new ArrayList<>(List.of(uid));
                    if (month != null) {
                        sql += " AND substr(date,1,7)=?";
                        params.add(month);
                    }
                    sql += " ORDER BY datetime(date) DESC, id DESC";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        for (int i = 0; i < params.size(); i++)
                            set(ps, i + 1, params.get(i));
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next())
                                list.add(new Expense(
                                        rs.getLong(1), rs.getLong(2), (rs.getObject(3) == null ? null : rs.getLong(3)),
                                        rs.getString(4), rs.getString(5), rs.getDouble(6), rs.getString(7)));
                        }
                    }
                    sendJson(ex, 200, list);
                    return;
                }

                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    JsonObject j = bodyJson(ex);
                    String name = j.get("name").getAsString().trim();
                    String category = j.get("category").getAsString().trim();
                    String date = j.has("date") && !j.get("date").isJsonNull() ? j.get("date").getAsString()
                            : Instant.now().toString();
                    Long share_id = j.has("share_id") && !j.get("share_id").isJsonNull() ? j.get("share_id").getAsLong()
                            : null;
                    double amount;
                    try {
                        amount = Double.parseDouble(j.get("amount").getAsString().replace(',', '.'));
                    } catch (Exception ee) {
                        sendJson(ex, 400, Map.of("error", "Neispravan iznos"));
                        return;
                    }
                    if (name.isEmpty() || category.isEmpty()) {
                        sendJson(ex, 400, Map.of("error", "Prazna polja"));
                        return;
                    }

                    Expense saved;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO expenses(user_id,share_id,name,category,amount,date) VALUES(?,?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, uid);
                        if (share_id == null)
                            ps.setNull(2, Types.INTEGER);
                        else
                            ps.setLong(2, share_id);
                        ps.setString(3, name);
                        ps.setString(4, category);
                        ps.setDouble(5, amount);
                        ps.setString(6, date);
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            long id = rs.next() ? rs.getLong(1) : 0;
                            saved = new Expense(id, uid, share_id, name, category, amount, date);
                        }
                    }
                    sendJson(ex, 201, saved);
                    return;
                }

                sendText(ex, 405, "");
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/expenses/", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;
                String p = ex.getRequestURI().getPath();
                if (p.matches("/api/expenses/\\d+") && "DELETE".equalsIgnoreCase(ex.getRequestMethod())) {
                    long id = Long.parseLong(p.substring(p.lastIndexOf('/') + 1));
                    try (PreparedStatement ps = conn
                            .prepareStatement("DELETE FROM expenses WHERE id=? AND user_id=?")) {
                        ps.setLong(1, id);
                        ps.setLong(2, uid);
                        ps.executeUpdate();
                    }
                    ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }
                sendText(ex, 404, "Not found");
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/stats", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;
                String month = q(ex, "month");
                Map<String, Double> map = new LinkedHashMap<>();
                String sql = "SELECT category, SUM(amount) FROM expenses WHERE user_id=?";
                List<Object> params = new ArrayList<>(List.of(uid));
                if (month != null) {
                    sql += " AND substr(date,1,7)=?";
                    params.add(month);
                }
                sql += " GROUP BY category ORDER BY 2 DESC";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (int i = 0; i < params.size(); i++)
                        set(ps, i + 1, params.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next())
                            map.put(rs.getString(1), rs.getDouble(2));
                    }
                }
                sendJson(ex, 200, map);
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        // SHARED (grupe + chat – skraćeno: invite link + chat)
        server.createContext("/api/shares", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;

                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    JsonObject j = bodyJson(ex);
                    String label = j.has("label") && !j.get("label").isJsonNull() ? j.get("label").getAsString()
                            : "Zajednički troškovi";
                    String token = PasswordUtil.randomToken(16);
                    long shareId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO expense_shares(owner_id,label,token) VALUES(?,?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setLong(1, uid);
                        ps.setString(2, label);
                        ps.setString(3, token);
                        ps.executeUpdate();
                        try (ResultSet rs = ps.getGeneratedKeys()) {
                            shareId = rs.next() ? rs.getLong(1) : 0;
                        }
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT OR IGNORE INTO expense_share_members(share_id,user_id) VALUES(?,?)")) {
                        ps.setLong(1, shareId);
                        ps.setLong(2, uid);
                        ps.executeUpdate();
                    }
                    sendJson(ex, 201, Map.of("id", shareId, "label", label, "invite_link",
                            origin(ex) + "/join.html?token=" + token));
                    return;
                }

                if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    List<Map<String, Object>> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement("""
                                SELECT s.id,s.label,s.owner_id
                                FROM expense_shares s
                                JOIN expense_share_members m ON m.share_id=s.id
                                WHERE m.user_id=?
                            """)) {
                        ps.setLong(1, uid);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next())
                                list.add(Map.of("id", rs.getLong(1), "label", rs.getString(2), "owner_id",
                                        rs.getLong(3)));
                        }
                    }
                    sendJson(ex, 200, list);
                    return;
                }

                sendText(ex, 405, "");
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/shares/join", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendText(ex, 405, "");
                    return;
                }
                JsonObject j = bodyJson(ex);
                String token = j.get("token").getAsString();
                Long shareId = scalarLongOrNull("SELECT id FROM expense_shares WHERE token=?", token);
                if (shareId == null) {
                    sendJson(ex, 404, Map.of("error", "Share ne postoji"));
                    return;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO expense_share_members(share_id,user_id) VALUES(?,?)")) {
                    ps.setLong(1, shareId);
                    ps.setLong(2, uid);
                    ps.executeUpdate();
                }
                sendJson(ex, 200, Map.of("status", "joined", "share_id", shareId));
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        server.createContext("/api/chat", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;
                if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    long shareId = Long.parseLong(q(ex, "shareId"));
                    List<Map<String, Object>> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id,from_user,body,sent_at FROM messages WHERE share_id=? ORDER BY id DESC LIMIT 100")) {
                        ps.setLong(1, shareId);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next())
                                list.add(Map.of("id", rs.getLong(1), "from_user", rs.getLong(2), "body",
                                        rs.getString(3), "sent_at", rs.getString(4)));
                        }
                    }
                    sendJson(ex, 200, list);
                    return;
                }
                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    JsonObject j = bodyJson(ex);
                    long shareId = j.get("share_id").getAsLong();
                    String body = j.get("body").getAsString();
                    try (PreparedStatement ps = conn
                            .prepareStatement("INSERT INTO messages(share_id,from_user,body) VALUES(?,?,?)")) {
                        ps.setLong(1, shareId);
                        ps.setLong(2, uid);
                        ps.setString(3, body);
                        ps.executeUpdate();
                    }
                    sendJson(ex, 201, Map.of("ok", true));
                    return;
                }
                sendText(ex, 405, "");
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        // SUBSCRIPTIONS
        server.createContext("/api/subscriptions", ex -> {
            try {
                cors(ex);
                Long uid = requireAuth(ex);
                if (uid == null)
                    return;

                if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    List<Subscription> list = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT id,user_id,service,amount,period_days,next_due,active FROM subscriptions WHERE user_id=? ORDER BY active DESC, next_due ASC")) {
                        ps.setLong(1, uid);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next())
                                list.add(new Subscription(
                                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getDouble(4), rs.getInt(5),
                                        rs.getString(6), rs.getInt(7) == 1));
                        }
                    }
                    sendJson(ex, 200, list);
                    return;
                }

                if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    JsonObject j = bodyJson(ex);
                    String service = j.get("service").getAsString();
                    double amount = Double.parseDouble(j.get("amount").getAsString().replace(',', '.'));
                    int period = j.has("period_days") ? j.get("period_days").getAsInt() : 30;
                    String nextDue = j.has("next_due") ? j.get("next_due").getAsString() : Instant.now().toString();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO subscriptions(user_id,service,amount,period_days,next_due,active) VALUES(?,?,?,?,?,1)")) {
                        ps.setLong(1, uid);
                        ps.setString(2, service);
                        ps.setDouble(3, amount);
                        ps.setInt(4, period);
                        ps.setString(5, nextDue);
                        ps.executeUpdate();
                    }
                    sendJson(ex, 201, Map.of("ok", true));
                    return;
                }

                if ("PUT".equalsIgnoreCase(ex.getRequestMethod())) {
                    JsonObject j = bodyJson(ex);
                    long id = j.get("id").getAsLong();
                    String nextDue = j.get("next_due").getAsString();
                    try (PreparedStatement ps = conn
                            .prepareStatement("UPDATE subscriptions SET next_due=? WHERE id=? AND user_id=?")) {
                        ps.setString(1, nextDue);
                        ps.setLong(2, id);
                        ps.setLong(3, uid);
                        ps.executeUpdate();
                    }
                    sendJson(ex, 200, Map.of("ok", true));
                    return;
                }

                sendText(ex, 405, "");
            } catch (Exception e) {
                e.printStackTrace();
                sendText(ex, 500, "Server error");
            }
        });

        // HEALTH
        server.createContext("/api/health", ex -> {
            cors(ex);
            if ("GET".equalsIgnoreCase(ex.getRequestMethod()))
                sendText(ex, 200, "OK");
            else
                sendText(ex, 405, "");
        });

        server.start();
        System.out.println("Server running on http://localhost:" + PORT);
    }

    // reminder loop
    static void reminderLoop() {
        while (true) {
            try {
                String now = OffsetDateTime.now().toString();
                String soon = OffsetDateTime.now().plusDays(3).toString();
                try (PreparedStatement ps = conn.prepareStatement(
                        """
                                    SELECT s.id, u.email, s.service, s.next_due
                                    FROM subscriptions s JOIN users u ON u.id=s.user_id
                                    WHERE s.active=1 AND s.next_due <= ? AND (s.last_notified IS NULL OR substr(s.last_notified,1,10) < substr(datetime('now'),1,10))
                                """)) {
                    ps.setString(1, soon);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long subId = rs.getLong(1);
                            String email = rs.getString(2);
                            String service = rs.getString(3);
                            String due = rs.getString(4);
                            String html = "<p>Podsetnik: " + service + " ističe do " + due + ".</p>";
                            mailer.send(email, "Podsetnik za pretplatu", html);
                            try (PreparedStatement u = conn
                                    .prepareStatement("UPDATE subscriptions SET last_notified=? WHERE id=?")) {
                                u.setString(1, now);
                                u.setLong(2, subId);
                                u.executeUpdate();
                            }
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            try {
                Thread.sleep(15000L);
            } catch (InterruptedException ignored) {
            }
        }
    }

    // utils
    static void serveFile(HttpExchange ex, Path file) throws IOException {
        cors(ex);
        send(ex, 200, Files.readAllBytes(file), mime(file.getFileName().toString()));
    }

    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
    }

    static void send(HttpExchange ex, int code, byte[] bytes, String ct) throws IOException {
        if (ct != null)
            ex.getResponseHeaders().add("Content-Type", ct);
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static void sendText(HttpExchange ex, int code, String text) throws IOException {
        send(ex, code, text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    static void sendJson(HttpExchange ex, int code, Object obj) throws IOException {
        send(ex, code, gson.toJson(obj).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    static String mime(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".html"))
            return "text/html; charset=utf-8";
        if (n.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (n.endsWith(".js"))
            return "application/javascript; charset=utf-8";
        if (n.endsWith(".png"))
            return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg"))
            return "image/jpeg";
        if (n.endsWith(".svg"))
            return "image/svg+xml";
        return "application/octet-stream";
    }

    static String cookie(HttpExchange ex, String name) {
        List<String> c = ex.getRequestHeaders().get("Cookie");
        if (c == null)
            return null;
        for (String s : c)
            for (String part : s.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name))
                    return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            }
        return null;
    }

    static void cookieOut(HttpExchange ex, String name, String val, int days) {
        String expires = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(ZonedDateTime.now(ZoneId.of("GMT")).plusDays(days));
        String cookie = name + "=" + val + "; Path=/; HttpOnly; SameSite=Lax; Expires=" + expires;
        ex.getResponseHeaders().add("Set-Cookie", cookie);
    }

    static Long sessionUser(String sid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT user_id, expires_at FROM sessions WHERE sid=?")) {
            ps.setString(1, sid);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return null;
                String exp = rs.getString(2);
                if (exp != null && OffsetDateTime.parse(exp).isBefore(OffsetDateTime.now()))
                    return null;
                return rs.getLong(1);
            }
        } catch (Exception e) {
            return null;
        }
    }

    static Long requireAuth(HttpExchange ex) throws IOException {
        String sid = cookie(ex, "sid");
        Long uid = (sid != null) ? sessionUser(sid) : null;
        if (uid == null) {
            sendText(ex, 401, "Auth required");
        }
        return uid;
    }

    static JsonObject bodyJson(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return JsonParser.parseString(body).getAsJsonObject();
    }

    static String q(HttpExchange ex, String name) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null)
            return null;
        for (String part : query.split("&")) {
            String[] kv = part.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name))
                return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        return null;
    }

    static long scalarLong(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++)
                set(ps, i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    static Long scalarLongOrNull(String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++)
                set(ps, i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next())
                    return null;
                return rs.getLong(1);
            }
        }
    }

    static void set(PreparedStatement ps, int i, Object v) throws SQLException {
        if (v == null)
            ps.setNull(i, Types.NULL);
        else if (v instanceof Long l)
            ps.setLong(i, l);
        else if (v instanceof Integer n)
            ps.setInt(i, n);
        else if (v instanceof Double d)
            ps.setDouble(i, d);
        else
            ps.setString(i, String.valueOf(v));
    }

    static String origin(HttpExchange ex) {
        String host = ex.getRequestHeaders().getFirst("Host");
        return "http://" + (host == null ? ("localhost:" + PORT) : host);
    }
}
