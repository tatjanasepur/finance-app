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

        String host = System.getenv("SMTP_HOST"); // smtp.gmail.com
        String port = System.getenv("SMTP_PORT"); // 587
        String user = System.getenv("SMTP_USER"); // tvoj gmail
        String pass = System.getenv("SMTP_PASS"); // app password (16 znakova)

        props.put("mail.smtp.host", host != null ? host : "");
        props.put("mail.smtp.port", port != null ? port : "587");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");
        props.put("mail.smtp.ssl.trust", host != null ? host : "");

        // timeout-i da ne visi zauvek
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });
    }

    /** Sinhrono slanje (ako baci grešku – hvataš je u WebServer-u) */
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

    /** Asinhrono slanje (opciono) */
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
