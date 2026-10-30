package dev.guestbook.verification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the Laravel customer app runs, and the Sanctum token used to call its API.
 */
@ConfigurationProperties("laravel")
public record LaravelProperties(String baseUrl, String apiToken) {
}
