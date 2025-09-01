import java.util.Properties;
import jakarta.mail.*;
import jakarta.mail.internet.*;

public class Mailer {
    private static final String SMTP_HOST = System.getenv().getOrDefault("SMTP_HOST", "");
    private static final String SMTP_USER = System.getenv().getOrDefault("SMTP_USER", "");
    private static final String SMTP_PASS = System.getenv().getOrDefault("SMTP_PASS", "");
    private static final String FROM_EMAIL = System.getenv().getOrDefault("FROM_EMAIL", "no-reply@finance.local");

    public static void send(String to, String subject, String body) {
        // Test režim – nema podešen SMTP -> ispiši „mejl“ u konzoli i izađi
        if (SMTP_HOST.isEmpty() || SMTP_USER.isEmpty() || SMTP_PASS.isEmpty()) {
            System.out.println("---- EMAIL (SIMULACIJA) ----");
            System.out.println("To: " + to);
            System.out.println("Subject: " + subject);
            System.out.println("Body:\n" + body);
            System.out.println("----------------------------");
            return;
        }

        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", SMTP_HOST);
            props.put("mail.smtp.port", "587");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(SMTP_USER, SMTP_PASS);
                }
            });

            Message msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress(FROM_EMAIL));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            msg.setSubject(subject);
            msg.setText(body);

            Transport.send(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
