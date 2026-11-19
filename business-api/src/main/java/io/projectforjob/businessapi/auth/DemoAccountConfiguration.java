package io.projectforjob.businessapi.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@Profile("demo & !prod")
class DemoAccountConfiguration {

    @Bean
    ApplicationRunner demoAccount(AccountMapper accounts, PasswordEncoder encoder,
            @Value("${PROJECT_DEMO_LOGIN:demo}") String login,
            @Value("${PROJECT_DEMO_PASSWORD:}") String password) {
        return arguments -> {
            var loginName = login.strip().toLowerCase(Locale.ROOT);
            if (!loginName.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalStateException("Set PROJECT_DEMO_LOGIN to a valid demo login name");
            }
            if (accounts.findByLoginName(loginName) != null) {
                return;
            }
            var length = password.getBytes(StandardCharsets.UTF_8).length;
            if (password.isBlank() || length < 12 || length > 72) {
                throw new IllegalStateException("Set PROJECT_DEMO_PASSWORD to 12–72 UTF-8 bytes to create the demo account");
            }
            accounts.createDemoAccount(loginName, "演示用户 · " + loginName, encoder.encode(password));
        };
    }
}
