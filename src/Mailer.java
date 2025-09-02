import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.mail.*;
import javax.mail.internet.*;

public class Mailer {
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);

    private static Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", System.getenv("SMTP_HOST"));
        props.put("mail.smtp.port", System.getenv("SMTP_PORT"));

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        System.getenv("SMTP_USER"),
                        System.getenv("SMTP_PASS"));
            }
        });
    }

    public static void sendHtml(String to, String subject, String html) throws MessagingException {
        Session session = createSession();
        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(System.getenv("MAIL_FROM")));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
        message.setSubject(subject);

        MimeBodyPart bodyPart = new MimeBodyPart();
        bodyPart.setContent(html, "text/html; charset=utf-8");

        Multipart multipart = new MimeMultipart();
        multipart.addBodyPart(bodyPart);

        message.setContent(multipart);

        Transport.send(message);
    }

    // 🔹 OVA metoda je falila
    public static void sendHtmlAsync(String to, String subject, String html) {
        pool.submit(() -> {
            try {
                sendHtml(to, subject, html);
                System.out.println("Email poslat ka " + to);
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
