package com.vedicmeet.appserver.auth.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Exact date-boundary port of utils/common-functions.js getZodiac. */
@Component
public class ZodiacCalculator {

    public String from(String dob) {
        if (dob == null || dob.isBlank()) return null;
        LocalDate date = parse(dob);
        if (date == null) return null;
        int day = date.getDayOfMonth();
        int month = date.getMonthValue();
        if ((month == 1 && day >= 20) || (month == 2 && day <= 18)) return "aquarius";
        if ((month == 2 && day >= 19) || (month == 3 && day <= 20)) return "pisces";
        if ((month == 3 && day >= 21) || (month == 4 && day <= 19)) return "aries";
        if ((month == 4 && day >= 20) || (month == 5 && day <= 20)) return "taurus";
        if ((month == 5 && day >= 21) || (month == 6 && day <= 20)) return "gemini";
        if ((month == 6 && day >= 21) || (month == 7 && day <= 22)) return "cancer";
        if ((month == 7 && day >= 23) || (month == 8 && day <= 22)) return "leo";
        if ((month == 8 && day >= 23) || (month == 9 && day <= 22)) return "virgo";
        if ((month == 9 && day >= 23) || (month == 10 && day <= 22)) return "libra";
        if ((month == 10 && day >= 23) || (month == 11 && day <= 21)) return "scorpio";
        if ((month == 11 && day >= 22) || (month == 12 && day <= 21)) return "sagittarius";
        return "capricorn";
    }

    private LocalDate parse(String value) {
        try { return LocalDate.parse(value.length() >= 10 ? value.substring(0, 10) : value); }
        catch (Exception ignored) {
            try { return Instant.parse(value).atZone(ZoneOffset.UTC).toLocalDate(); }
            catch (Exception ignoredAgain) { return null; }
        }
    }
}
