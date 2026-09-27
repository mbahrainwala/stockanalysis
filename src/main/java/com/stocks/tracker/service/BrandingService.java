package com.stocks.tracker.service;

import com.stocks.tracker.model.AppSetting;
import com.stocks.tracker.repository.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The instance's display name (e.g. "Acme Stock Manager"), set by the admin and shown to everyone. */
@Service
public class BrandingService {

    public static final String DEFAULT_NAME = "Stock Tracker";
    private static final String KEY_COMPANY_NAME = "app.companyName";

    private final AppSettingRepository settings;

    public BrandingService(AppSettingRepository settings) {
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public String companyName() {
        return settings.findById(KEY_COMPANY_NAME).map(AppSetting::getValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(DEFAULT_NAME);
    }

    @Transactional
    public String saveCompanyName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            clean = DEFAULT_NAME;
        }
        if (clean.length() > 100) {
            clean = clean.substring(0, 100);
        }
        settings.save(new AppSetting(KEY_COMPANY_NAME, clean));
        return clean;
    }
}
