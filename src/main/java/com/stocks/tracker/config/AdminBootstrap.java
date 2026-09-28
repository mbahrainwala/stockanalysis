package com.stocks.tracker.config;

import com.stocks.tracker.model.User;
import com.stocks.tracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates the first admin user on a fresh install; the schema itself is managed by Flyway. */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder,
                          @Value("${app.admin.username:admin}") String adminUsername,
                          @Value("${app.admin.password:admin}") String adminPassword) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() == 0) {
            users.save(new User(adminUsername, passwordEncoder.encode(adminPassword), User.Role.ADMIN));
            log.info("Created initial admin user \"{}\". Change its password after logging in.", adminUsername);
        }
    }
}
