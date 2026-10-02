package com.scx.review;

import java.security.Principal;
import java.util.Map;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final UserRepository users;
    public AuthController(UserRepository users) { this.users = users; }
    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/me") public Map<String, Object> me(Principal principal) {
        UserAccount u = users.findByUsername(principal.getName()).orElseThrow();
        return Map.of("id", u.id, "username", u.username, "role", u.role);
    }
}
