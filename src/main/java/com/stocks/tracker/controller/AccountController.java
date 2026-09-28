package com.stocks.tracker.controller;

import com.stocks.tracker.model.User;
import com.stocks.tracker.repository.UserRepository;
import com.stocks.tracker.security.CurrentUserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Self-service account actions for the signed-in user (as opposed to AdminController's admin-only user management). */
@Controller
public class AccountController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final CurrentUserService currentUser;

    public AccountController(UserRepository users, PasswordEncoder passwordEncoder, CurrentUserService currentUser) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.currentUser = currentUser;
    }

    @PostMapping("/account/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                                 @RequestParam String confirmPassword, RedirectAttributes redirectAttributes) {
        try {
            User user = currentUser.currentUser();
            if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
                throw new IllegalArgumentException("Current password is incorrect.");
            }
            if (newPassword == null || newPassword.isBlank()) {
                throw new IllegalArgumentException("New password is required.");
            }
            if (!newPassword.equals(confirmPassword)) {
                throw new IllegalArgumentException("New passwords do not match.");
            }
            if (newPassword.equals(currentPassword)) {
                throw new IllegalArgumentException("New password must be different from the current password.");
            }
            user.setPasswordHash(passwordEncoder.encode(newPassword));
            users.save(user);
            redirectAttributes.addFlashAttribute("success", "Password changed.");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not change password: " + e.getMessage());
        }
        return "redirect:/";
    }
}
