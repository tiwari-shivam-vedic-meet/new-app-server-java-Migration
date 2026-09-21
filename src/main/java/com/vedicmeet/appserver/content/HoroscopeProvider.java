package com.vedicmeet.appserver.content;

import java.util.Map;

/** External daily/weekly/monthly/yearly horoscope provider seam. */
public interface HoroscopeProvider {
    Map<String, Object> get(String sign, String type);
}
