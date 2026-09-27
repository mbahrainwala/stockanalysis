package com.stocks.tracker.security;

import com.stocks.tracker.model.User;
import com.stocks.tracker.repository.UserRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** Resolves the signed-in user from the security context for services that need to scope data. */
@Service
public class CurrentUserService {

    private final UserRepository users;

    public CurrentUserService(UserRepository users) {
        this.users = users;
    }

    public User currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new IllegalStateException("No signed-in user.");
        }
        return users.findByUsernameIgnoreCase(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Signed-in user no longer exists."));
    }

    public Long currentUserId() {
        return currentUser().getId();
    }

    public boolean isAdmin() {
        return currentUser().getRole() == User.Role.ADMIN;
    }
}
