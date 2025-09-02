import java.util.Properties;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

public class Mailer {

    private static Session createSession() {
        Properties props = new Properties();

        // Čita iz environment varijabli koje si postavila na Railway
        String host = System.getenv("SMTP_HOST");
        String port = System.getenv("SMTP_PORT"); // npr 587
        String user = System.getenv("SMTP_USER");
        String pass = System.getenv("SMTP_PASS");

        props.put("mail.smtp.host", host != null ? host : "");
        props.put("mail.smtp.port", port != null ? port : "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        // Gmail zahteva TLS:
        props.put("mail.smtp.ssl.trust", host != null ? host : "");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });
    }

    /** Sinhrono slanje (blokira dok ne pošalje) */
    public static void sendHtml(String to, String subject, String html) throws MessagingException {
        Session session = createSession();

        Message message = new MimeMessage(session);
        String from = System.getenv("MAIL_FROM");
        if (from == null || from.isEmpty()) {
            from = System.getenv("SMTP_USER"); // fallback
        }

        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);

        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setContent(html, "text/html; charset=UTF-8");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(bodyPart);

        message.setContent(multipart);

        Transport.send(message);
        System.out.println("Mail sent to: " + to + " subject=" + subject);
    }

    /** Asinhrono slanje (poziva se iz WebServer-a) */
    public static void sendHtmlAsync(String to, String subject, String html) {
        new Thread(() -> {
            try {
                sendHtml(to, subject, html);
            } catch (Exception e) {
                System.err.println("SMTP send failed: " + e.getMessage());
                e.printStackTrace();
            }
        }, "smtp-sender").start();
    }
}
