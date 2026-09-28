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
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * Finds and drops any unique index on exactly {@code column} in {@code table}, regardless of
     * its database-generated name. Uses {@link DatabaseMetaData} (rather than a hand-written
     * {@code information_schema} query) so it works the same way against both H2 and MySQL, and so
     * it also finds an index that a foreign key has claimed as its own supporting index: H2 reuses
     * a pre-existing unique index for that instead of creating a separate one, which then reports
     * the index's owning constraint as the foreign key rather than as UNIQUE and silently defeats a
     * query that only looks for {@code constraint_type = 'UNIQUE'}.
     */
    private void dropLegacySingleColumnUniqueIndex(String table, String column) {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            String actualTable = resolveTableName(meta, table);
            if (actualTable == null) {
                return;
            }
            Map<String, List<String>> indexColumns = new LinkedHashMap<>();
            try (ResultSet idx = meta.getIndexInfo(conn.getCatalog(), null, actualTable, true, false)) {
                while (idx.next()) {
                    String indexName = idx.getString("INDEX_NAME");
                    String columnName = idx.getString("COLUMN_NAME");
                    if (indexName == null || columnName == null) {
                        continue;
                    }
                    indexColumns.computeIfAbsent(indexName, k -> new ArrayList<>()).add(columnName);
                }
            }
            for (Map.Entry<String, List<String>> entry : indexColumns.entrySet()) {
                List<String> columns = entry.getValue();
                if (columns.size() == 1 && columns.get(0).equalsIgnoreCase(column)) {
                    dropIndexAndDependentForeignKey(conn, meta, actualTable, entry.getKey(), column);
                }
            }
        } catch (SQLException e) {
            log.warn("Could not look up legacy unique index on {}({}): {}", table, column, e.getMessage());
        }
    }

    private String resolveTableName(DatabaseMetaData meta, String logicalName) throws SQLException {
        try (ResultSet rs = meta.getTables(null, null, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name.equalsIgnoreCase(logicalName)) {
                    return name;
                }
            }
        }
        return null;
    }

    /** A foreign key from one column of a table to one column of another. */
    private record ForeignKey(String name, String column, String referencedTable, String referencedColumn) {
    }

    private void dropIndexAndDependentForeignKey(Connection conn, DatabaseMetaData meta, String table,
                                                  String indexName, String column) throws SQLException {
        ForeignKey dependent = null;
        try (ResultSet fks = meta.getImportedKeys(conn.getCatalog(), null, table)) {
            while (fks.next()) {
                if (column.equalsIgnoreCase(fks.getString("FKCOLUMN_NAME"))) {
                    dependent = new ForeignKey(fks.getString("FK_NAME"), fks.getString("FKCOLUMN_NAME"),
                            fks.getString("PKTABLE_NAME"), fks.getString("PKCOLUMN_NAME"));
                    break;
                }
            }
        }
        if (dependent != null && !dropForeignKey(conn, table, dependent.name())) {
            // Couldn't free the index from its foreign key; leave it in place rather than fail startup.
            return;
        }
        // Dropping a foreign key that was using this index as its own support index takes the index
        // down with it, so only attempt (and warn about) an explicit drop when it's still there.
        if (dependent == null || indexExists(meta, conn, table, indexName)) {
            if (dropIndex(conn, table, indexName)) {
                log.info("Dropped legacy unique index {} on {}({}), superseded by a per-user constraint.",
                        indexName, table, column);
            }
        } else {
            log.info("Legacy unique index {} on {}({}) was cleared along with its foreign key; recreating the key.",
                    indexName, table, column);
        }
        if (dependent != null) {
            String fk = dependent.name();
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD CONSTRAINT " + fk + " FOREIGN KEY (" + dependent.column()
                        + ") REFERENCES " + dependent.referencedTable() + " (" + dependent.referencedColumn() + ")");
            } catch (SQLException e) {
                log.warn("Dropped foreign key {} on {} to recreate its index but could not add it back: {}",
                        fk, table, e.getMessage());
            }
        }
    }

    private boolean indexExists(DatabaseMetaData meta, Connection conn, String table, String indexName) throws SQLException {
        try (ResultSet idx = meta.getIndexInfo(conn.getCatalog(), null, table, false, false)) {
            while (idx.next()) {
                if (indexName.equalsIgnoreCase(idx.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean dropForeignKey(Connection conn, String table, String name) {
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP CONSTRAINT " + name);
            return true;
        } catch (SQLException ignored) {
            // Fall through: some MySQL versions only accept the FOREIGN KEY-specific syntax.
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP FOREIGN KEY " + name);
            return true;
        } catch (SQLException e) {
            log.warn("Could not drop foreign key {} on {} blocking a legacy index cleanup: {}", name, table, e.getMessage());
            return false;
        }
    }

    private boolean dropIndex(Connection conn, String table, String name) {
        try (Statement st = conn.createStatement()) {
            st.execute("DROP INDEX " + name);
            return true;
        } catch (SQLException ignored) {
            // Fall through: MySQL requires the owning table in a DROP INDEX statement.
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " DROP INDEX " + name);
            return true;
        } catch (SQLException e2) {
            log.warn("Could not drop legacy unique index {} on {}: {}", name, table, e2.getMessage());
            return false;
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
