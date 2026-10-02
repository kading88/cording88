package com.scx.review;

import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true, length = 64)
    public String username;
    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;
    @Column(nullable = false, length = 16)
    public String role;
}
