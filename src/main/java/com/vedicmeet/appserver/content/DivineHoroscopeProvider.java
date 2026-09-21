package com.vedicmeet.appserver.content;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Config-driven port of utils/functions/horoscope.js; provider failures remain best effort. */
@Component
public class DivineHoroscopeProvider implements HoroscopeProvider {
    private static final Logger log = LoggerFactory.getLogger(DivineHoroscopeProvider.class);

    private final String apiKey;
    private final Map<String, String> urls;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    public DivineHoroscopeProvider(
            @Value("${vedicmeet.horoscope.api-key:}") String apiKey,
            @Value("${vedicmeet.horoscope.daily-url:https://divineapi.com/api/1.0/get_daily_horoscope.php}") String daily,
            @Value("${vedicmeet.horoscope.weekly-url:https://divineapi.com/api/1.0/get_weekly_horoscope.php}") String weekly,
            @Value("${vedicmeet.horoscope.monthly-url:https://divineapi.com/api/1.0/get_monthly_horoscope.php}") String monthly,
            @Value("${vedicmeet.horoscope.yearly-url:https://divineapi.com/api/1.0/get_yearly_horoscope.php}") String yearly,
            ObjectMapper mapper) {
        this(apiKey, Map.of("TODAY", daily, "WEEKLY", weekly, "MONTHLY", monthly,
                "YEARLY", yearly), mapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    DivineHoroscopeProvider(String apiKey, Map<String, String> urls, ObjectMapper mapper,
                            HttpClient http) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.urls = urls;
        this.mapper = mapper;
        this.http = http;
    }

    @Override
    public Map<String, Object> get(String sign, String type) {
        try {
            String url = urls.get(type);
            if (url == null || url.isBlank() || apiKey.isBlank()) return null;
            Map<String, String> fields = new LinkedHashMap<>();
            switch (type) {
                case "TODAY" -> fields.put("date", LocalDate.now().toString());
                case "WEEKLY" -> fields.put("week", "current");
                case "MONTHLY" -> fields.put("month", "current");
                case "YEARLY" -> fields.put("year", "current");
                default -> { return null; }
            }
            fields.put("sign", sign);
            fields.put("api_key", apiKey);
            fields.put("timezone", "5.5");
            String body = fields.entrySet().stream()
                    .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                    .reduce((a, b) -> a + "&" + b).orElse("");
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) return null;
            return mapper.readValue(response.body(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception error) {
            log.warn("horoscope provider request failed type={} sign={}", type, sign);
            return null; // Node catches and returns undefined.
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
