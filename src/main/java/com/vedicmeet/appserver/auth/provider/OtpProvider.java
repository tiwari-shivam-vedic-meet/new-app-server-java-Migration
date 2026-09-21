package com.vedicmeet.appserver.auth.provider;

import java.util.Map;

/** Provider boundary equivalent to the Node msg91-api dependency. */
public interface OtpProvider {

    OtpResult send(String phonePrefix, String phone);
    OtpResult verify(String phonePrefix, String phone, String otp);
    OtpResult resend(String phonePrefix, String phone);

    record OtpResult(boolean success, String message, Map<String, Object> data) { }
}
