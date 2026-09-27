package com.stocks.tracker.controller;

import com.stocks.tracker.service.BrandingService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    private final BrandingService brandingService;

    public AuthController(BrandingService brandingService) {
        this.brandingService = brandingService;
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        Model model) {
        model.addAttribute("companyName", brandingService.companyName());
        model.addAttribute("error", error != null);
        model.addAttribute("loggedOut", logout != null);
        return "login";
    }
}
