package com.stocks.tracker.service;

import com.stocks.tracker.dto.PortfolioRow;
import com.stocks.tracker.dto.PortfolioView;
import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.model.Holding;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.Stock;
import com.stocks.tracker.model.TradingAccount;
import com.stocks.tracker.repository.HoldingRepository;
import com.stocks.tracker.repository.StockRepository;
import com.stocks.tracker.repository.TradingAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class PortfolioService {

    private static final Logger log = LoggerFactory.getLogger(PortfolioService.class);

    private final TradingAccountRepository accountRepository;
    private final StockRepository stockRepository;
    private final HoldingRepository holdingRepository;
    private final MarketDataService marketDataService;

    public PortfolioService(TradingAccountRepository accountRepository,
                             StockRepository stockRepository,
                             HoldingRepository holdingRepository,
                             MarketDataService marketDataService) {
        this.accountRepository = accountRepository;
        this.stockRepository = stockRepository;
        this.holdingRepository = holdingRepository;
        this.marketDataService = marketDataService;
    }

    @Transactional(readOnly = true)
    public PortfolioView buildPortfolioView() {
        List<TradingAccount> accounts = accountRepository.findAll();
        List<Holding> holdings = holdingRepository.findAll();

        Map<Long, PortfolioRow> rowsByStockId = new LinkedHashMap<>();
        for (Holding holding : holdings) {
            Stock stock = holding.getStock();
            PortfolioRow row = rowsByStockId.computeIfAbsent(stock.getId(),
                    id -> new PortfolioRow(stock.getId(), stock.getSymbol(), stock.getMarket(),
                            stock.getCompanyName(), stock.getCurrency(), stock.getCurrentPrice()));

            BigDecimal price = stock.getCurrentPrice() == null ? BigDecimal.ZERO : stock.getCurrentPrice();
            BigDecimal value = holding.getShares().multiply(price).setScale(2, RoundingMode.HALF_UP);
            row.putAccountShares(holding.getTradingAccount().getId(), holding.getShares(), value);
        }

        List<PortfolioRow> rows = rowsByStockId.values().stream()
                .sorted((a, b) -> a.getSymbol().compareToIgnoreCase(b.getSymbol()))
                .toList();

        // Grand totals are kept separate per currency: summing USD, CAD and
        // INR holdings into one number would be meaningless.
        Map<String, BigDecimal> grandTotalsByCurrency = new TreeMap<>();
        for (PortfolioRow row : rows) {
            grandTotalsByCurrency.merge(row.getCurrency(), row.getTotalValue(), BigDecimal::add);
        }

        return new PortfolioView(accounts, rows, grandTotalsByCurrency);
    }

    @Transactional
    public TradingAccount createAccount(String name, String broker) {
        return accountRepository.save(new TradingAccount(name.trim(), broker == null ? null : broker.trim()));
    }

    /**
     * Plain-text description of every holding, grouped by account, for use in an LLM prompt.
     * Returns null when there are no holdings. Values are in each stock's own currency.
     */
    @Transactional(readOnly = true)
    public String buildPortfolioSummary() {
        List<Holding> holdings = holdingRepository.findAll();
        if (holdings.isEmpty()) {
            return null;
        }
        Map<String, List<Holding>> byAccount = new TreeMap<>();
        Map<String, BigDecimal> totalsByCurrency = new TreeMap<>();
        for (Holding h : holdings) {
            TradingAccount a = h.getTradingAccount();
            String label = a.getName() + (a.getBroker() == null || a.getBroker().isBlank() ? "" : " (" + a.getBroker() + ")");
            byAccount.computeIfAbsent(label, k -> new java.util.ArrayList<>()).add(h);
        }

        StringBuilder sb = new StringBuilder("Portfolio holdings (values are in each stock's own currency):\n");
        for (Map.Entry<String, List<Holding>> entry : byAccount.entrySet()) {
            sb.append("\nAccount: ").append(entry.getKey()).append('\n');
            entry.getValue().sort((x, y) -> x.getStock().getSymbol().compareToIgnoreCase(y.getStock().getSymbol()));
            for (Holding h : entry.getValue()) {
                Stock s = h.getStock();
                BigDecimal price = s.getCurrentPrice();
                String currency = s.getCurrency() == null ? s.getMarket().getDefaultCurrency() : s.getCurrency();
                sb.append("- ").append(s.getSymbol()).append(" [").append(s.getMarket().getLabel()).append("] ")
                        .append(s.getCompanyName()).append(": ")
                        .append(h.getShares().stripTrailingZeros().toPlainString()).append(" shares");
                if (price != null) {
                    BigDecimal value = h.getShares().multiply(price).setScale(2, RoundingMode.HALF_UP);
                    totalsByCurrency.merge(currency, value, BigDecimal::add);
                    sb.append(", price ").append(price.setScale(2, RoundingMode.HALF_UP).toPlainString()).append(' ').append(currency)
                            .append(", value ").append(value.toPlainString()).append(' ').append(currency);
                }
                BigDecimal cost = h.getAverageCost();
                if (cost != null && cost.signum() > 0) {
                    sb.append(", avg cost ").append(cost.setScale(2, RoundingMode.HALF_UP).toPlainString());
                    if (price != null) {
                        BigDecimal pct = price.subtract(cost).multiply(BigDecimal.valueOf(100))
                                .divide(cost, 1, RoundingMode.HALF_UP);
                        sb.append(" (").append(pct.signum() > 0 ? "+" : "").append(pct.toPlainString()).append("% vs cost)");
                    }
                }
                sb.append('\n');
            }
        }
        sb.append("\nTotal value by currency:\n");
        totalsByCurrency.forEach((cur, total) -> sb.append("- ").append(cur).append(": ").append(total.toPlainString()).append('\n'));
        return sb.toString();
    }

    @Transactional
    public TradingAccount updateAccount(Long accountId, String name, String broker) {
        TradingAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        String newName = name == null ? "" : name.trim();
        if (newName.isEmpty()) {
            throw new IllegalArgumentException("Account name cannot be empty.");
        }
        accountRepository.findByNameIgnoreCase(newName).ifPresent(existing -> {
            if (!existing.getId().equals(accountId)) {
                throw new IllegalArgumentException("An account named \"" + newName + "\" already exists.");
            }
        });
        account.setName(newName);
        account.setBroker(broker == null || broker.isBlank() ? null : broker.trim());
        return account;
    }

    /** Deletes the account; its holdings are removed via cascade. */
    @Transactional
    public String deleteAccount(Long accountId) {
        TradingAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        accountRepository.delete(account);
        return account.getName();
    }

    @Transactional
    public QuoteResult lookupSymbol(String symbol, Market market) {
        return marketDataService.fetchQuote(symbol, market);
    }

    public List<com.stocks.tracker.dto.SymbolMatch> searchSymbols(String query, Market market) {
        return marketDataService.searchByName(query, market);
    }

    /**
     * Adds shares of a stock to an account. If the stock isn't known yet it is
     * looked up and created; if the account already holds the stock, the
     * position is averaged into the existing holding.
     */
    @Transactional
    public void addShares(Long accountId, String symbolInput, Market market, BigDecimal shares, BigDecimal pricePaid) {
        if (shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Shares must be greater than zero");
        }
        if (market == null) {
            throw new IllegalArgumentException("Market must be specified");
        }

        TradingAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown trading account: " + accountId));

        String symbol = symbolInput.trim().toUpperCase();
        Stock stock = stockRepository.findBySymbolIgnoreCaseAndMarket(symbol, market).orElseGet(() -> {
            QuoteResult quote = marketDataService.fetchQuote(symbol, market);
            Stock newStock = new Stock(quote.symbol(), quote.market(), quote.companyName());
            newStock.setCurrentPrice(quote.price());
            newStock.setCurrency(quote.currency());
            newStock.setPriceUpdatedAt(LocalDateTime.now());
            return stockRepository.save(newStock);
        });

        BigDecimal costBasis = pricePaid != null ? pricePaid
                : stock.getCurrentPrice() != null ? stock.getCurrentPrice() : BigDecimal.ZERO;

        Holding holding = holdingRepository.findByTradingAccountIdAndStockId(accountId, stock.getId())
                .orElseGet(() -> new Holding(account, stock, BigDecimal.ZERO, BigDecimal.ZERO));

        BigDecimal existingShares = holding.getShares();
        BigDecimal existingCost = holding.getAverageCost() == null ? BigDecimal.ZERO : holding.getAverageCost();
        BigDecimal newTotalShares = existingShares.add(shares);
        BigDecimal blendedCost = existingShares.multiply(existingCost)
                .add(shares.multiply(costBasis))
                .divide(newTotalShares, 4, RoundingMode.HALF_UP);

        holding.setShares(newTotalShares);
        holding.setAverageCost(blendedCost);
        holdingRepository.save(holding);
    }

    /**
     * Sets the exact share count for an account's position in a stock
     * (spreadsheet-style edit). Zero removes the holding.
     */
    @Transactional
    public void setShares(Long accountId, Long stockId, BigDecimal shares) {
        if (shares == null || shares.signum() < 0) {
            throw new IllegalArgumentException("Shares cannot be negative");
        }
        TradingAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown trading account: " + accountId));
        Stock stock = stockRepository.findById(stockId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown stock: " + stockId));
        var existing = holdingRepository.findByTradingAccountIdAndStockId(accountId, stockId);
        if (shares.signum() == 0) {
            existing.ifPresent(holdingRepository::delete);
            return;
        }
        Holding holding = existing.orElseGet(() -> new Holding(account, stock, BigDecimal.ZERO,
                stock.getCurrentPrice() != null ? stock.getCurrentPrice() : BigDecimal.ZERO));
        holding.setShares(shares);
        holdingRepository.save(holding);
    }

    /**
     * Refreshes the current market price for every stock currently held.
     * Failures for individual symbols are logged and skipped so one bad
     * symbol doesn't block the rest of the refresh.
     */
    @Transactional
    public int refreshAllPrices() {
        List<Stock> stocks = stockRepository.findAll();
        int updated = 0;
        for (Stock stock : stocks) {
            try {
                QuoteResult quote = marketDataService.fetchQuote(stock.getSymbol(), stock.getMarket());
                stock.setCurrentPrice(quote.price());
                stock.setCurrency(quote.currency());
                stock.setPriceUpdatedAt(LocalDateTime.now());
                if (quote.companyName() != null && !quote.companyName().isBlank()) {
                    stock.setCompanyName(quote.companyName());
                }
                stockRepository.save(stock);
                updated++;
            } catch (Exception e) {
                log.warn("Skipping price refresh for {} ({}): {}", stock.getSymbol(), stock.getMarket(), e.getMessage());
            }
        }
        return updated;
    }
}
