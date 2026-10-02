package com.scx.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AccountInitializer implements CommandLineRunner {
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final String adminPassword;
    private final String reviewerPassword;
    public AccountInitializer(UserRepository users, PasswordEncoder encoder,
            @Value("${app.admin-password}") String adminPassword,
            @Value("${app.reviewer-password}") String reviewerPassword) {
        this.users = users; this.encoder = encoder;
        this.adminPassword = adminPassword; this.reviewerPassword = reviewerPassword;
    }
    @Override @Transactional public void run(String... args) {
        create("admin", "ADMIN", adminPassword);
        create("reviewer", "REVIEWER", reviewerPassword);
    }
    private void create(String name, String role, String password) {
        if (users.findByUsername(name).isPresent()) return;
        if (password == null || password.length() < 10) throw new IllegalStateException("Initial password must contain at least 10 characters");
        UserAccount u = new UserAccount(); u.username = name;
        u.passwordHash = encoder.encode(password); u.role = role; users.save(u);
    }
}
