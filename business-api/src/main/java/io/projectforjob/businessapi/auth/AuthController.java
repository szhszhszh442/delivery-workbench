package io.projectforjob.businessapi.auth;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import io.projectforjob.businessapi.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.authentication.AccountStatusException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
class AuthController {

    private final AccountMapper accounts;
    private final AuthenticationManager authenticationManager;
    private final HttpSessionSecurityContextRepository securityContexts;
    private final SecurityContextLogoutHandler logoutHandler;
    private final ChangeSessionIdAuthenticationStrategy sessionIdStrategy = new ChangeSessionIdAuthenticationStrategy();
    private final CsrfAuthenticationStrategy csrfStrategy;

    AuthController(AccountMapper accounts, AuthenticationManager authenticationManager,
            HttpSessionSecurityContextRepository securityContexts, HttpSessionCsrfTokenRepository csrfTokens,
            SecurityContextLogoutHandler logoutHandler) {
        this.accounts = accounts;
        this.authenticationManager = authenticationManager;
        this.securityContexts = securityContexts;
        this.logoutHandler = logoutHandler;
        this.csrfStrategy = new CsrfAuthenticationStrategy(csrfTokens);
    }

    @GetMapping("/auth/csrf")
    ApiResponse<CsrfResponse> csrf(CsrfToken token) {
        return ApiResponse.success(new CsrfResponse(token.getToken(), token.getHeaderName()));
    }

    @PostMapping(path = "/auth/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> login(@RequestBody LoginRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        if (body.loginName() == null || !body.loginName().isString()
                || body.password() == null || !body.password().isString()) {
            return invalidRequest();
        }
        var loginName = body.loginName().asString().strip().toLowerCase(Locale.ROOT);
        var password = body.password().asString();
        if (loginName.isEmpty() || loginName.length() > 64 || password.isBlank()
                || password.length() > 72
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            return invalidRequest();
        }

        Authentication authenticated;
        try {
            authenticated = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(loginName, password));
        } catch (BadCredentialsException | AccountStatusException failure) {
            return failure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号或密码错误");
        }
        var account = accounts.findByLoginName(authenticated.getName());
        if (account == null || !account.active()) {
            return failure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "账号或密码错误");
        }

        // Keep only the canonical login name and authorities in the persisted session.
        var sessionAuthentication = UsernamePasswordAuthenticationToken.authenticated(
                account.loginName(), null, authenticated.getAuthorities());
        sessionIdStrategy.onAuthentication(sessionAuthentication, request, response);
        csrfStrategy.onAuthentication(sessionAuthentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(sessionAuthentication);
        SecurityContextHolder.setContext(context);
        securityContexts.saveContext(context, request, response);
        return ResponseEntity.ok(ApiResponse.success(UserResponse.from(account)));
    }

    @GetMapping("/me")
    ResponseEntity<?> me(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        var account = accounts.findByLoginName(authentication.getName());
        if (account == null || !account.active()) {
            logoutHandler.logout(request, response, authentication);
            return failure(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "请先登录");
        }
        return ResponseEntity.ok(ApiResponse.success(UserResponse.from(account)));
    }

    @PostMapping(path = "/auth/logout", consumes = MediaType.APPLICATION_JSON_VALUE)
    ApiResponse<LogoutResponse> logout(@RequestBody LogoutRequest body, Authentication authentication,
            HttpServletRequest request, HttpServletResponse response) {
        logoutHandler.logout(request, response, authentication);
        return ApiResponse.success(new LogoutResponse(true));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse.ErrorResponse> invalidRequest() {
        return failure(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请检查请求格式和字段");
    }

    private ResponseEntity<ApiResponse.ErrorResponse> failure(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(ApiResponse.ErrorResponse.of(code, message));
    }

    record LoginRequest(JsonNode loginName, JsonNode password) {
    }

    record LogoutRequest() {
    }

    record LogoutResponse(boolean loggedOut) {
    }

    record CsrfResponse(String token, String headerName) {
    }

    record UserResponse(String id, String loginName, String displayName) {
        static UserResponse from(AccountMapper.Account account) {
            return new UserResponse(Long.toString(account.id()), account.loginName(), account.displayName());
        }
    }
}
