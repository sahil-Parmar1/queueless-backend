package com.queueless.auth_service.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import com.queueless.auth_service.auth.dto.AdminLoginRequest;
import com.queueless.auth_service.user.UserRepository;

class AuthServiceAdminTest {

    @Test
    void loginAdminIssuesAdminJwtWhenConfiguredCredentialsMatch() {
        JwtService jwtService = new JwtService("test-jwt-secret-key-at-least-32-bytes-long");
        AuthService authService = new AuthService(
                mock(UserRepository.class),
                mock(PasswordEncoder.class),
                jwtService,
                mock(JwtDecoder.class)
        );
        ReflectionTestUtils.setField(authService, "adminUsername", "Sahil123");
        ReflectionTestUtils.setField(authService, "adminPassword", "Sahil@123");

        AdminLoginRequest validReq = new AdminLoginRequest();
        validReq.setUsername("Sahil123");
        validReq.setPassword("Sahil@123");

        Map<String, Object> result = authService.loginAdmin(validReq);
        assertEquals("Sahil123", result.get("username"));
        assertEquals("ADMIN", result.get("role"));
        String token = (String) result.get("token");
        assertNotNull(token);
        assertTrue(jwtService.isTokenValid(token));
        assertEquals("Sahil123", jwtService.extractEmail(token));
        assertEquals("ADMIN", jwtService.extractRole(token));

        AdminLoginRequest badReq = new AdminLoginRequest();
        badReq.setUsername("Sahil123");
        badReq.setPassword("wrong-pass");
        assertThrows(ResponseStatusException.class, () -> authService.loginAdmin(badReq));
    }
}
