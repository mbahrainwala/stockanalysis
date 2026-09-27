# Stock Tracker

A Spring Boot + Thymeleaf app for tracking stock holdings across multiple trading accounts.

## Features

- Multi-user: each person signs in with their own account and sees only their own trading accounts and speculation list. New users can only be created by an admin (see "Users & login" below).
- Multiple trading accounts, each holding shares of one or more stocks.
- One-page portfolio view: rows are stocks, columns are accounts, last column is the total across all accounts.
- Stocks from the **US**, **Canada** (TSX / TSX-V), and **India** (NSE / BSE) — pick the market when adding a holding or looking up a symbol. Grand totals are grouped per currency (USD / CAD / INR) since they can't be meaningfully summed together.
- Look up a stock by ticker symbol (via Yahoo Finance's public quote endpoint) to pull its current price, currency and company name.
- "Refresh Market Prices" updates the price of every stock shown on the page, regardless of market.
- "Download CSV" exports your portfolio as a spreadsheet, with one column per trading account.
- Runs against a local file-based H2 database out of the box; switch to MySQL for a real deployment.

## Running locally (H2 file database)

The `h2` Spring profile is active by default, so no external database is required.

```bash
mvn spring-boot:run
```

The app starts on http://localhost:8080. Data is persisted to `./data/stocktracker.mv.db` (ignored by git) so it survives restarts. The H2 web console is available at http://localhost:8080/h2-console (JDBC URL `jdbc:h2:file:./data/stocktracker`, user `sa`, no password).

## Users & login

The app requires sign-in. On first startup (when no users exist yet), a default admin account is
created automatically:

- **Username:** `admin`
- **Password:** `admin`

**Change this password immediately after your first login** — sign in, open the **Admin** tab,
and use "Reset Password" on the `admin` user. This matters more once the app is reachable by
anyone else on your network (e.g. hosted on a NAS).

To set a different initial username/password instead (e.g. before first startup, or when
deploying fresh), set environment variables:

```bash
export ADMIN_USERNAME=myname
export ADMIN_PASSWORD=a-real-password
```

These are only used to create the admin account when no users exist yet; they have no effect on
an existing install. Only the admin can create additional users, reset passwords, change roles,
and set the company name shown in the header and on the login page, all from the **Admin** tab.
The **AI Setup** tab is admin-only too, since the AI provider/model is a single shared
configuration for everyone.

## Running against MySQL

1. Create a MySQL server reachable from this machine (or let the app create the schema via `createDatabaseIfNotExist=true`).
2. Set connection details via environment variables (defaults shown):

   ```bash
   export DB_HOST=localhost
   export DB_PORT=3306
   export DB_NAME=stock_tracker
   export DB_USERNAME=root
   export DB_PASSWORD=secret
   ```

3. Run with the `mysql` profile active:

   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=mysql
   ```

   Or, for a packaged jar:

   ```bash
   java -jar target/stock-tracker-1.0.0.jar --spring.profiles.active=mysql
   ```

## Notes on the market data source

Stock symbol lookups and price refreshes call Yahoo Finance's public, unauthenticated chart endpoint. It requires no API key, but it's an unofficial endpoint that Yahoo could change or rate-limit at any time. If it becomes unreliable, swap the implementation in `MarketDataService` for a provider you have an API key for (e.g. Alpha Vantage, Finnhub, IEX Cloud).

## Building

```bash
mvn clean package
java -jar target/stock-tracker-1.0.0.jar
```
