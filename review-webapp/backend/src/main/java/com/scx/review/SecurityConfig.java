package com.scx.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(UserRepository users) {
        return username -> {
            UserAccount u = users.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
            return User.withUsername(u.username).password(u.passwordHash).roles(u.role).build();
        };
    }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper json, RecordService records) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/csrf", "/api/auth/login", "/error").permitAll()
                .requestMatchers(HttpMethod.DELETE, "/api/records/*").hasRole("ADMIN")
                .requestMatchers("/api/**").hasAnyRole("ADMIN", "REVIEWER")
                .anyRequest().permitAll())
            // Keep CSRF protection; the frontend fetches a token from /csrf and sends it in request headers.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, err) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    json.writeValue(res.getWriter(), Map.of("code", "UNAUTHORIZED", "message", "Sign in first."));
                })
                .accessDeniedHandler((req, res, err) -> {
                    res.setStatus(403); res.setContentType("application/json;charset=UTF-8");
                    json.writeValue(res.getWriter(), Map.of("code", "FORBIDDEN", "message", "Access is denied or the session has expired. Refresh the page."));
                }))
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .successHandler((req, res, auth) -> {
                    res.setContentType("application/json;charset=UTF-8");
                    json.writeValue(res.getWriter(), Map.of("ok", true));
                })
                .failureHandler((req, res, err) -> {
                    res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                    json.writeValue(res.getWriter(), Map.of("code", "BAD_CREDENTIALS", "message", "The username or password is incorrect."));
                }))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .addLogoutHandler((req, res, auth) -> { if (auth != null) records.releaseAll(auth.getName()); })
                .deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .requestCache(cache -> cache.disable());
        return http.build();
    }
}
