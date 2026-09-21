package com.vedicmeet.appserver.auth.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request DTOs for the legacy /auth contract. Aliases are resolved by AuthRequestValidator. */
public final class AuthRequests {

    private AuthRequests() { }

    public static class PhoneAliases {
        public String phone;
        public String mobile;
        public String phonePrefix;
        public String countryCode;
    }

    public static class SendOtpRequest extends PhoneAliases {
        public String userType;
        public String deviceToken;
        public String isSecondaryNumber;
    }

    public static class VerifyOtpRequest extends PhoneAliases {
        public String userType;
        public String otp;
        public Boolean isSecondaryNumber;
    }

    public static class LoginRequest extends PhoneAliases {
        public String userType;
        public String email;
        public String otp;
        public String deviceToken;
        public String fcmToken;
        public String voipToken;
        public String deviceType;
        public String deviceUUID;
        public Boolean receivePromotionAndOffers;
    }

    public static class UserRegistrationRequest extends PhoneAliases {
        public String dob;
        public String deviceUUID;
        public String deviceToken;
        public String fcmToken;
        public String gender;
        public String name;
        public Object problems;
        public String timeOfBirth;
        public String placeOfBirth;
        public Object placeLatLong;
        public String email;
        public String source;
        public String utm_source;
        public String utm_medium;
        public String utm_campaign;
        public String utm_term;
        public String utm_content;
        public final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void extra(String key, Object value) { extra.put(key, value); }
    }

    public static class SocialLoginRequest {
        public String userType;
        public String email;
        public String provider;
        public String identityToken;
        public String deviceToken;
        public String fcmToken;
        public String voipToken;
        public String deviceType;
    }

    public static class LogoutRequest {
        public String userType;
        public String logOutFrom;
        public String fcmToken;
        public String deviceToken;
    }

    public static class DeviceRegistrationRequest {
        public String userType;
        public String fcmToken;
        public String deviceToken;
        public String deviceUUID;
        public String deviceType;
        public String voipToken;
    }

    public static class ConsultantSignupRequest extends PhoneAliases {
        public String name;
        public String userName;
        public String email;
        public String gender;
        public String dateOfBirth;
        public String dob;
        public String consType;
        public Object language;
        public Object primarySkills;
        public Object otherSkills;
        public Object expertise;
        public Object problems;
        public Object avgLiveBrier;
        public String deviceToken;
        public String fcmToken;
        public String address;
        public String city;
        public String state;
        public String country;
        public String pincode;
        public String referCode;
        public Object score;
        public String reasonOfOnboard;
        public String mainSourceIncome;
        public String qualification;
        public String highestQualification;
        public String learnAstrologyFrom;
        public String instaLink;
        public String faceBookLink;
        public String linkedinLink;
        public String youTubeLink;
        public Object foreignCountryNo;
        public String workingFullTimeJob;
        public String greaterChallengeAndConquer;
        public String bio;
        public Boolean isRefer;
        public Object experienceYear;
        public Object dailyWorkHour;
        public String hearAboutUs;
        public String otherOnlinePlatformWork;
        public String minimumEarningExpectation;
        public Object price;
        public String whenHearAboutUs;
        public String onlinePlatformWork;
        public String onlinePlatformName;
        public final Map<String, Object> extra = new LinkedHashMap<>();

        @JsonAnySetter
        public void extra(String key, Object value) { extra.put(key, value); }
    }

    public static class ConsultantApprovalRequest {
        public String consultantId;
        public Object approve;
    }
}
