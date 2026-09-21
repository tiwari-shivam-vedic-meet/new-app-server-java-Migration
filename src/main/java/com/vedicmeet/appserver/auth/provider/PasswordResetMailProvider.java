package com.vedicmeet.appserver.auth.provider;

/** Provider seam for admin reset-password email. */
public interface PasswordResetMailProvider {
    void send(String email, String name, String resetToken);
}
