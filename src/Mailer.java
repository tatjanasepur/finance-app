
// src/Mailer.java
import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

public class Mailer {

    /**
     * Pošalji HTML email. Ako SMTP promenljive nisu podešene ili slanje padne,
     * ispiše "SIMULACIJA" u konzoli (fallback), da aplikacija nastavi da radi.
     */
    public static void send(String to, String subject, String htmlBody) {
        String host = getenv("SMTP_HOST");
        String port = getenv("SMTP_PORT"); // npr. 587
        String user = getenv("SMTP_USER");
        String pass = getenv("SMTP_PASS");
        String from = getenv("MAIL_FROM"); // npr. tsolutionsdev@outlook.com
        if (from == null || from.isBlank())
            from = user;

        boolean haveSmtp = notEmpty(host) && notEmpty(port) && notEmpty(user) && notEmpty(pass) && notEmpty(from);

        if (!haveSmtp) {
            simulate(to, subject, htmlBody, "Nedostaju SMTP varijable");
            return;
        }

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", host);
            props.put("mail.smtp.port", port);
            props.put("mail.smtp.auth", "true");
            // Office365/Outlook:
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
            // Ako se koristi moderni TLS, često pomaže trust:
            props.put("mail.smtp.ssl.trust", host);

            boolean debug = "1".equals(getenv("MAIL_DEBUG"));
            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(user, pass);
                }
            });
            session.setDebug(debug);

            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(from));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, false));
            msg.setSubject(subject);
            msg.setContent(htmlBody, "text/html; charset=utf-8");

            Transport.send(msg);
            if (debug)
                System.out.println("Email poslat OK → " + to);
        } catch (Exception e) {
            // Fallback da ne zapnemo na produkciji ako SMTP zezne
            simulate(to, subject, htmlBody, "Greška pri slanju: " + e.getMessage());
        }
    }

    // -------- helpers --------
    private static String getenv(String k) {
        try {
            return System.getenv(k);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isBlank();
    }

    private static void simulate(String to, String subject, String html, String reason) {
        System.out.println("---- EMAIL (SIMULACIJA) ----");
        System.out.println("Razlog: " + reason);
        System.out.println("To: " + to);
        System.out.println("Subject: " + subject);
        System.out.println("Body:");
        System.out.println(html);
        System.out.println("----------------------------");
    }
}
