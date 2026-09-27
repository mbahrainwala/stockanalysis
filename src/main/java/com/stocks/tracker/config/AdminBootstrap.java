package com.stocks.tracker.config;

import com.stocks.tracker.model.User;
import com.stocks.tracker.repository.SavedAnalysisRepository;
import com.stocks.tracker.repository.SpeculationEntryRepository;
import com.stocks.tracker.repository.TradingAccountRepository;
import com.stocks.tracker.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates the first admin user on a fresh or just-upgraded install, and assigns that admin as
 * the owner of any trading account, speculation entry, or saved analysis left over from before
 * multi-user support existed (so existing data is never lost).
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final TradingAccountRepository accounts;
    private final SpeculationEntryRepository speculationEntries;
    private final SavedAnalysisRepository savedAnalyses;
    private final PasswordEncoder passwordEncoder;
    private final DataSource dataSource;
    private final String adminUsername;
    private final String adminPassword;

    public AdminBootstrap(UserRepository users, TradingAccountRepository accounts,
                          SpeculationEntryRepository speculationEntries, SavedAnalysisRepository savedAnalyses,
                          PasswordEncoder passwordEncoder, DataSource dataSource,
                          @Value("${app.admin.username:admin}") String adminUsername,
                          @Value("${app.admin.password:admin}") String adminPassword) {
        this.users = users;
        this.accounts = accounts;
        this.speculationEntries = speculationEntries;
        this.savedAnalyses = savedAnalyses;
        this.passwordEncoder = passwordEncoder;
        this.dataSource = dataSource;
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Hibernate's ddl-auto:update only adds columns/constraints; it never drops the old
        // single-column unique constraints that predate multi-user support, so those are
        // dropped here before any per-user duplicate (e.g. two users both named "RBC") is tried.
        dropLegacySingleColumnUniqueIndex("trading_account", "name");
        dropLegacySingleColumnUniqueIndex("speculation_entry", "stock_id");
        createAdminAndBackfill();
    }

    private void createAdminAndBackfill() {
        User admin;
        if (users.count() == 0) {
            admin = users.save(new User(adminUsername, passwordEncoder.encode(adminPassword), User.Role.ADMIN));
            log.info("Created initial admin user \"{}\". Change its password after logging in.", adminUsername);
        } else {
            admin = users.findByUsernameIgnoreCase(adminUsername).orElse(null);
        }
        if (admin == null) {
            return;
        }
        backfillOwners(admin);
    }

    /**
     * Finds and drops any unique constraint on exactly {@code column} in {@code table}, regardless
     * of its database-generated name. Uses the standard {@code information_schema} views (rather
     * than {@code DatabaseMetaData}) so the same query works against both H2 and MySQL.
     */
    private void dropLegacySingleColumnUniqueIndex(String table, String column) {
        String sql = "SELECT tc.constraint_name, count(*) as col_count "
                + "FROM information_schema.table_constraints tc "
                + "JOIN information_schema.key_column_usage kcu "
                + "  ON tc.constraint_name = kcu.constraint_name AND tc.table_name = kcu.table_name "
                + "WHERE tc.constraint_type = 'UNIQUE' AND upper(tc.table_name) = upper(?) "
                + "GROUP BY tc.constraint_name "
                + "HAVING count(*) = 1 AND max(upper(kcu.column_name)) = upper(?)";
        List<String> constraintNames = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             var ps = conn.prepareStatement(sql)) {
            ps.setString(1, table);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    constraintNames.add(rs.getString("constraint_name"));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not look up legacy unique constraints on {}({}): {}", table, column, e.getMessage());
            return;
        }
        for (String name : constraintNames) {
            dropConstraint(table, name);
        }
    }

    private void dropConstraint(String table, String name) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
            log.info("Dropped legacy unique constraint {} on {}, superseded by a per-user constraint.", name, table);
            return;
        } catch (SQLException ignored) {
            // Fall through: MySQL represents a plain UNIQUE constraint as an index instead.
        }
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP INDEX " + name);
            log.info("Dropped legacy unique index {} on {}, superseded by a per-user constraint.", name, table);
        } catch (SQLException e2) {
            log.warn("Could not drop legacy unique constraint/index {} on {}: {}", name, table, e2.getMessage());
        }
    }

    private void backfillOwners(User admin) {
        int accountsFixed = 0;
        for (var account : accounts.findAll()) {
            if (account.getOwner() == null) {
                account.setOwner(admin);
                accounts.save(account);
                accountsFixed++;
            }
        }
        int speculationFixed = 0;
        for (var entry : speculationEntries.findAll()) {
            if (entry.getOwner() == null) {
                entry.setOwner(admin);
                speculationEntries.save(entry);
                speculationFixed++;
            }
        }
        int analysesFixed = 0;
        for (var analysis : savedAnalyses.findAll()) {
            if (analysis.getOwner() == null) {
                analysis.setOwner(admin);
                savedAnalyses.save(analysis);
                analysesFixed++;
            }
        }
        if (accountsFixed + speculationFixed + analysesFixed > 0) {
            log.info("Assigned {} trading account(s), {} speculation entr(y/ies) and {} saved analysis/es "
                            + "with no owner to \"{}\".",
                    accountsFixed, speculationFixed, analysesFixed, admin.getUsername());
        }
    }
}
