package com.bookkeeping.service;

/**
 * @author zhuxiao
 */
public interface WeatherService {

    Weather todayWeather(String city);

    void dailyWeather();

    record Weather(String city, String description, String tempC, String feelsLikeC,
                   String minTempC, String maxTempC, String humidity, String windKmph) {
    }
}
