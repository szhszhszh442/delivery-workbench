package io.projectforjob.businessapi.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthFlowTests {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PasswordEncoder passwords;

    @Autowired
    ObjectMapper json;

    @Test
    void authenticatesWithCsrfPersistsAndRotatesSessionAndRevokesCookieOnLogout() throws Exception {
        var username = "auth-test-" + UUID.randomUUID();
        var password = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO app_user(login_name, display_name, password_hash, status)
                VALUES (?, ?, ?, 'ACTIVE')
                """, username, "测试用户", passwords.encode(password));

        var cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (var browser = HttpClient.newBuilder()
                .proxy(ProxySelector.of(null))
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(4))
                .build()) {
            var login = json.createObjectNode().put("loginName", username).put("password", password);
            assertError(get(browser, "/me"), 401, "UNAUTHENTICATED");
            assertError(post(browser, "/auth/login", login.toString(), null), 403, "CSRF_INVALID");

            var csrf = csrf(browser);
            var anonymousCookie = sessionCookie(cookies);
            assertThat(anonymousCookie).isNotBlank();
            assertError(post(browser, "/auth/login", login.deepCopy().put("role", "OWNER").toString(), csrf),
                    400, "INVALID_REQUEST");
            assertError(post(browser, "/auth/login", login.deepCopy().put("password", 123).toString(), csrf),
                    400, "INVALID_REQUEST");
            assertError(post(browser, "/auth/login", login.deepCopy().put("password", "wrong").toString(), csrf),
                    401, "UNAUTHENTICATED");
            jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE login_name = ?", username);
            assertError(post(browser, "/auth/login", login.toString(), csrf), 401, "UNAUTHENTICATED");
            jdbc.update("UPDATE app_user SET status = 'ACTIVE' WHERE login_name = ?", username);

            var loginResponse = post(browser, "/auth/login", login.toString(), csrf);
            assertThat(loginResponse.statusCode()).isEqualTo(200);
            var user = json.readTree(loginResponse.body()).get("data");
            assertThat(user.get("id").isString()).isTrue();
            assertThat(user.get("loginName").asString()).isEqualTo(username);
            assertThat(user.get("displayName").asString()).isEqualTo("测试用户");
            assertThat(loginResponse.body()).doesNotContain(password, "passwordHash");
            var authenticatedCookie = sessionCookie(cookies);
            assertThat(authenticatedCookie).isNotEqualTo(anonymousCookie);
            assertThat(loginResponse.headers().allValues("Set-Cookie")).anySatisfy(header ->
                    assertThat(header).contains("HttpOnly", "SameSite=Lax"));
            assertThat(sessionCount(username)).isEqualTo(1);
            assertThat(get(browser, "/me").statusCode()).isEqualTo(200);

            assertError(post(browser, "/auth/logout", "{}", csrf), 403, "CSRF_INVALID");
            var logout = post(browser, "/auth/logout", "{}", csrf(browser));
            assertThat(logout.statusCode()).isEqualTo(200);
            assertThat(json.readTree(logout.body()).get("data").get("loggedOut").booleanValue()).isTrue();
            assertThat(sessionCount(username)).isZero();
            assertError(get(browser, "/me"), 401, "UNAUTHENTICATED");

            try (var formerSession = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build()) {
                var oldCookieRequest = request("/me").header("Cookie", "SESSION=" + authenticatedCookie)
                        .GET().build();
                assertError(formerSession.send(oldCookieRequest, HttpResponse.BodyHandlers.ofString()),
                        401, "UNAUTHENTICATED");
            }
        } finally {
            jdbc.update("DELETE FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?", username);
            jdbc.update("DELETE FROM app_user WHERE login_name = ?", username);
        }
    }

    private long sessionCount(String username) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION WHERE PRINCIPAL_NAME = ?",
                Long.class, username);
    }

    private String sessionCookie(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> "SESSION".equals(cookie.getName()))
                .findFirst().orElseThrow().getValue();
    }

    private Csrf csrf(HttpClient client) throws Exception {
        var response = get(client, "/auth/csrf");
        assertThat(response.statusCode()).isEqualTo(200);
        var data = json.readTree(response.body()).get("data");
        return new Csrf(data.get("headerName").asString(), data.get("token").asString());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, Csrf csrf) throws Exception {
        var builder = request(path).header("Content-Type", "application/json");
        if (csrf != null) {
            builder.header(csrf.headerName(), csrf.token());
        }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(5));
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as("response: %s", response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("error").get("code").asString()).isEqualTo(code);
    }

    private record Csrf(String headerName, String token) {
    }
}
