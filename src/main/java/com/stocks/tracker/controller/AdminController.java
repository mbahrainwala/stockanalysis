package com.stocks.tracker.controller;

import com.stocks.tracker.model.User;
import com.stocks.tracker.repository.UserRepository;
import com.stocks.tracker.security.CurrentUserService;
import com.stocks.tracker.service.BrandingService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Admin-only user management and branding; access is enforced by SecurityConfig's /admin/** matcher. */
@Controller
@RequestMapping("/admin")
public class AdminController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final BrandingService brandingService;
    private final CurrentUserService currentUser;

    public AdminController(UserRepository users, PasswordEncoder passwordEncoder,
                           BrandingService brandingService, CurrentUserService currentUser) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.brandingService = brandingService;
        this.currentUser = currentUser;
    }

    @PostMapping("/branding")
    public String saveBranding(@RequestParam String companyName, RedirectAttributes redirectAttributes) {
        String saved = brandingService.saveCompanyName(companyName);
        redirectAttributes.addFlashAttribute("success", "Company name set to \"" + saved + "\".");
        return "redirect:/";
    }

    @PostMapping("/users")
    public String createUser(@RequestParam String username, @RequestParam String password,
                             @RequestParam(defaultValue = "USER") User.Role role,
                             RedirectAttributes redirectAttributes) {
        try {
            String cleanUsername = username == null ? "" : username.trim();
            if (cleanUsername.isEmpty()) {
                throw new IllegalArgumentException("Username is required.");
            }
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("Password is required.");
            }
            users.findByUsernameIgnoreCase(cleanUsername).ifPresent(u -> {
                throw new IllegalArgumentException("A user named \"" + cleanUsername + "\" already exists.");
            });
            users.save(new User(cleanUsername, passwordEncoder.encode(password), role));
            redirectAttributes.addFlashAttribute("success", "Created user \"" + cleanUsername + "\".");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not create user: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/users/{id}/password")
    public String resetPassword(@PathVariable Long id, @RequestParam String password,
                                RedirectAttributes redirectAttributes) {
        try {
            if (password == null || password.isBlank()) {
                throw new IllegalArgumentException("Password is required.");
            }
            User user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
            user.setPasswordHash(passwordEncoder.encode(password));
            users.save(user);
            redirectAttributes.addFlashAttribute("success", "Password reset for \"" + user.getUsername() + "\".");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not reset password: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/users/{id}/role")
    public String changeRole(@PathVariable Long id, @RequestParam User.Role role,
                             RedirectAttributes redirectAttributes) {
        try {
            User user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
            if (user.getId().equals(currentUser.currentUserId()) && role != User.Role.ADMIN) {
                throw new IllegalArgumentException("You cannot remove your own admin role.");
            }
            user.setRole(role);
            users.save(user);
            redirectAttributes.addFlashAttribute("success", "\"" + user.getUsername() + "\" is now " + role + ".");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not change role: " + e.getMessage());
        }
        return "redirect:/";
    }

    @PostMapping("/users/{id}/enabled")
    public String setEnabled(@PathVariable Long id, @RequestParam boolean enabled,
                             RedirectAttributes redirectAttributes) {
        try {
            User user = users.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found."));
            if (!enabled && user.getId().equals(currentUser.currentUserId())) {
                throw new IllegalArgumentException("You cannot disable your own account.");
            }
            user.setEnabled(enabled);
            users.save(user);
            redirectAttributes.addFlashAttribute("success",
                    "\"" + user.getUsername() + "\" is now " + (enabled ? "enabled" : "disabled") + ".");
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", "Could not update user: " + e.getMessage());
        }
        return "redirect:/";
    }
}
