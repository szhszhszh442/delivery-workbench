package io.projectforjob.businessapi.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import io.projectforjob.businessapi.api.ApiResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class AuthSecurity {

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(AccountMapper accounts) {
        return loginName -> {
            var account = accounts.findByLoginName(loginName);
            if (account == null) {
                throw new UsernameNotFoundException("Account not found");
            }
            return User.withUsername(account.loginName())
                    .password(account.passwordHash())
                    .disabled(!account.active())
                    .roles("USER")
                    .build();
        };
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionSecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityContextLogoutHandler logoutHandler(HttpSessionSecurityContextRepository securityContexts) {
        var handler = new SecurityContextLogoutHandler();
        handler.setSecurityContextRepository(securityContexts);
        return handler;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            HttpSessionSecurityContextRepository securityContexts,
            HttpSessionCsrfTokenRepository csrfTokens,
            ObjectMapper json) throws Exception {
        http
                .securityContext(context -> context.securityContextRepository(securityContexts))
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokens))
                .requestCache(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, failure) ->
                                writeError(json, response, 401, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, failure) -> {
                            if (failure instanceof CsrfException) {
                                writeError(json, response, 403, "CSRF_INVALID", "请求令牌已失效，请刷新后重试");
                            } else {
                                writeError(json, response, 403, "FORBIDDEN", "没有操作权限");
                            }
                        }));
        return http.build();
    }

    private void writeError(ObjectMapper json, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        json.writeValue(response.getOutputStream(), ApiResponse.ErrorResponse.of(code, message));
    }
}
