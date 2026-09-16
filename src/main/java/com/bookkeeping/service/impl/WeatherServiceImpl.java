package com.bookkeeping.service.impl;

import com.bookkeeping.constant.MessageType;
import com.bookkeeping.service.MessageService;
import com.bookkeeping.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * @author zhuxiao
 */
@Service
@Slf4j
public class WeatherServiceImpl implements WeatherService {

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final MessageService messageService;

    /** pinyin → 中文城市名；city.json 加载失败时保持空表，展示时回退 wttr.in 原名 */
    private Map<String, String> cityMap = Map.of();

    /**
     * 策展中文文案表（定位：覆写 + 表外码补充，不再追求穷举 wttr.in 码空间）。
     * 中文解析链：lang_zh（仅认含汉字的）→ 本表 → 英文关键词中文化 KEYWORD_TEXT → 英文原文。
     * 149 为实测表外码（Smoky haze，烟/霾类）；350/374/377 ice pellets 类修正为冰粒、362/365 sleet 类修正为雨夹雪阵。
     * 注意：Map.ofEntries 遇重复 key 在类初始化时即抛异常（阻断启动），补码时勿重复。
     */
    private static final Map<String, String> CODE_TEXT = Map.ofEntries(
            Map.entry("113", "晴"), Map.entry("116", "多云"), Map.entry("119", "阴"),
            Map.entry("122", "多云转阴"), Map.entry("143", "雾"), Map.entry("149", "霾"),
            Map.entry("176", "小阵雨"),
            Map.entry("179", "小阵雪"), Map.entry("182", "雨夹雪"), Map.entry("185", "冻雨"),
            Map.entry("200", "雷阵雨"), Map.entry("227", "吹雪/风雪"), Map.entry("230", "暴风雪"),
            Map.entry("248", "雾"), Map.entry("260", "冻雾"), Map.entry("263", "小雨"),
            Map.entry("266", "小雨"), Map.entry("281", "冻毛毛雨"), Map.entry("284", "冻雨"),
            Map.entry("293", "小雨"), Map.entry("296", "小雨"), Map.entry("299", "中雨"),
            Map.entry("302", "中雨"), Map.entry("305", "大雨"), Map.entry("308", "大雨"),
            Map.entry("311", "冻雨"), Map.entry("314", "冻雨"), Map.entry("320", "雨夹雪"),
            Map.entry("323", "雨夹雪"), Map.entry("326", "小雪"), Map.entry("329", "中雪"),
            Map.entry("332", "中雪"), Map.entry("335", "大雪"), Map.entry("338", "大雪"),
            Map.entry("350", "冰粒"), Map.entry("353", "小阵雨"), Map.entry("356", "中阵雨"),
            Map.entry("359", "大阵雨"), Map.entry("362", "雨夹雪阵"), Map.entry("365", "雨夹雪阵"),
            Map.entry("368", "小阵雪"), Map.entry("371", "大阵雪"), Map.entry("374", "阵冰粒"),
            Map.entry("377", "阵冰粒"), Map.entry("386", "雷阵雨"), Map.entry("389", "强雷阵雨"),
            Map.entry("392", "雷阵雪"), Map.entry("395", "强雷阵雪"));

    /**
     * 英文关键词 → 中文兜底表（与前端 weatherIconKey 同一思路）：表外码且 wttr.in 无中文时，
     * 按关键词生成族级中文，避免直接给用户看英文；顺序即优先级，小写包含匹配。
     */
    private static final List<String[]> KEYWORD_TEXT = List.of(
            new String[] {"thunder", "雷阵雨"},
            new String[] {"heavy rain", "大雨"},
            new String[] {"torrential", "大雨"},
            new String[] {"heavy snow", "大雪"},
            new String[] {"drizzle", "毛毛雨"},
            new String[] {"rain", "阵雨"},
            new String[] {"sleet", "雨夹雪"},
            new String[] {"blizzard", "暴风雪"},
            new String[] {"snow", "降雪"},
            new String[] {"shower", "阵雨"},
            new String[] {"fog", "雾"},
            new String[] {"mist", "雾"},
            new String[] {"haze", "霾"},
            new String[] {"smoke", "霾"},
            new String[] {"dust", "霾"},
            new String[] {"sand", "霾"},
            new String[] {"overcast", "阴"},
            new String[] {"cloud", "多云"},
            new String[] {"sun", "晴"},
            new String[] {"clear", "晴"});

    public WeatherServiceImpl(ObjectMapper objectMapper, MessageService messageService) {
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = objectMapper;
        this.messageService = messageService;
        try (InputStream stream = new ClassPathResource("city.json").getInputStream()) {
            this.cityMap = objectMapper.readValue(stream, new TypeReference<>() {
            });
        } catch (IOException ex) {
            log.error("Failed to load city.json", ex);
        }
    }

    @Override
    public void dailyWeather() {
        LocalDate now = LocalDate.now(ZoneId.systemDefault());
        if (messageService.exists(MessageType.WEATHER, now.atStartOfDay(), now.plusDays(1).atStartOfDay())) {
            return;
        }
        Weather weather = todayWeather(null);
        if (Objects.nonNull(weather)) {
            messageService.pushMessage("每日天气", objectMapper.writeValueAsString(weather), null, MessageType.WEATHER, null);
        }
    }

    @Override
    public Weather todayWeather(String city) {
        HttpRequest request = buildRequest(city);
        try {
            HttpResponse<String> response
                    = httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charset.defaultCharset()));
            if (!Objects.equals(response.statusCode(), HttpStatus.OK.value())) {
                log.error("Failed to get weather, status code: {}", response.statusCode());
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode current = root.path("current_condition").path(0);
            JsonNode day = root.path("weather").path(0);
            if (current.isMissingNode()) {
                return null;
            }
            String safeCity = root.path("nearest_area").path(0).path("areaName").path(0).path("value").asString();
            String code = current.path("weatherCode").asString("");
            String english = current.path("weatherDesc").path(0).path("value").asString("");
            // lang_zh 实测对未翻译码返回英文回声，仅当含汉字才采纳；其余依次走策展表、关键词中文化、英文原文
            String langZh = current.path("lang_zh").path(0).path("value").asString("");
            String description = isChinese(langZh) ? langZh : CODE_TEXT.getOrDefault(code, "");
            if (StringUtils.isBlank(description)) {
                description = keywordText(english);
            }
            if (StringUtils.isBlank(description)) {
                description = english;
            }
            if (StringUtils.isBlank(description)) {
                description = "天气未知";
            }
            return new Weather(
                    cityMap.getOrDefault(safeCity, safeCity),
                    description,
                    current.path("temp_C").asString(""),
                    current.path("FeelsLikeC").asString(""),
                    day.path("mintempC").asString(""),
                    day.path("maxtempC").asString(""),
                    current.path("humidity").asString(""),
                    current.path("windspeedKmph").asString(""));
        } catch (Exception e) {
            log.error("Failed to get weather", e);
        }
        return null;
    }

    private HttpRequest buildRequest(String city) {
        URI uri = createURI(StringUtils.isNotBlank(city) ? "/" + city : "");
        return HttpRequest
                .newBuilder()
                .uri(uri)
                .header(HttpHeaders.USER_AGENT, "Mozilla/5.0")
                .GET().build();
    }

    private URI createURI(String city) {
        // lang=zh 让 wttr.in 返回 lang_zh 字段（当前多为英文回声，上游补齐翻译后自动生效）
        return URI.create("https://wttr.in" + city + "?format=j1&lang=zh");
    }

    /** 是否含汉字：lang_zh 未翻译时是英文回声，不能当作中文采纳 */
    private static boolean isChinese(String text) {
        return StringUtils.isNotBlank(text) && text.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    /** 按英文关键词中文化（族级文案），仅兜底表外码；无命中返回空串 */
    private static String keywordText(String english) {
        String lower = StringUtils.lowerCase(english);
        for (String[] pair : KEYWORD_TEXT) {
            if (lower.contains(pair[0])) {
                return pair[1];
            }
        }
        return "";
    }

    public void init() {
        dailyWeather();
    }
}
