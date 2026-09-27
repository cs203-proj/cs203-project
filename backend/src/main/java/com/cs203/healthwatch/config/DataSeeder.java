package com.cs203.healthwatch.config;

import com.cs203.healthwatch.model.User;
import com.cs203.healthwatch.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.seed.admin-username:#{null}}")
    private String adminUsername;

    @Value("${app.seed.admin-password:#{null}}")
    private String adminPassword;

    public DataSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminUsername == null || adminPassword == null) {
            System.out.println("Skipping admin seed — ADMIN_SEED_USERNAME/ADMIN_SEED_PASSWORD not set.");
            return;
        }

        // idempotent: only seed if it doesn't already exist
        if (userRepository.findByUsername(adminUsername).isEmpty()) {
            User admin = new User(adminUsername, passwordEncoder.encode(adminPassword), "admin");
            userRepository.save(admin);
            System.out.println("Seeded admin user: " + adminUsername);
        } else {
            System.out.println("Admin user already exists, skipping seed.");
        }
    }
}