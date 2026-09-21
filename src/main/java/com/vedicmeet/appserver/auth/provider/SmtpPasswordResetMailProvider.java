package com.vedicmeet.appserver.auth.provider;

import com.vedicmeet.appserver.auth.exception.AuthException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP adapter; disabled by default so local/tests cannot accidentally send real mail. */
@Component
public class SmtpPasswordResetMailProvider implements PasswordResetMailProvider {

    private final ObjectProvider<JavaMailSender> senders;
    private final boolean enabled;
    private final String from;
    private final String resetUrl;

    public SmtpPasswordResetMailProvider(
            ObjectProvider<JavaMailSender> senders,
            @Value("${vedicmeet.auth.mail.enabled:false}") boolean enabled,
            @Value("${vedicmeet.auth.mail.from:}") String from,
            @Value("${vedicmeet.auth.reset-url:}") String resetUrl) {
        this.senders = senders;
        this.enabled = enabled;
        this.from = from;
        this.resetUrl = resetUrl;
    }

    @Override
    public void send(String email, String name, String resetToken) {
        JavaMailSender sender = senders.getIfAvailable();
        if (!enabled || blank(from) || blank(resetUrl) || sender == null) {
            throw new AuthException("Password reset email is not configured");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("Password Reset Link");
        message.setText("Hello " + (blank(name) ? "Admin" : name)
                + ",\n\nUse this link to reset your password:\n" + resetUrl + "/" + resetToken
                + "\n\nIf you did not request this, ignore this email.");
        sender.send(message);
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
