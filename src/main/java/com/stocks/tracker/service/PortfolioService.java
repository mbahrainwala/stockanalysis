package com.stocks.tracker.service;

import com.stocks.tracker.dto.PortfolioRow;
import com.stocks.tracker.dto.PortfolioView;
import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.model.Currencies;
import com.stocks.tracker.model.Holding;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.PurchaseLot;
import com.stocks.tracker.model.Stock;
import com.stocks.tracker.model.TradingAccount;
import com.stocks.tracker.model.User;
import com.stocks.tracker.repository.HoldingRepository;
import com.stocks.tracker.repository.PurchaseLotRepository;
import com.stocks.tracker.repository.StockRepository;
import com.stocks.tracker.repository.TradingAccountRepository;
import com.stocks.tracker.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
    private final PurchaseLotRepository lotRepository;
    private final MarketDataService marketDataService;
    private final ExchangeRateService exchangeRates;
    private final SpeculationService speculation;
    private final CurrentUserService currentUser;

    public PortfolioService(TradingAccountRepository accountRepository,
                             StockRepository stockRepository,
                             HoldingRepository holdingRepository,
                             PurchaseLotRepository lotRepository,
                             MarketDataService marketDataService,
                             ExchangeRateService exchangeRates,
                             SpeculationService speculation,
                             CurrentUserService currentUser) {
        this.accountRepository = accountRepository;
        this.stockRepository = stockRepository;
        this.holdingRepository = holdingRepository;
        this.lotRepository = lotRepository;
        this.marketDataService = marketDataService;
        this.exchangeRates = exchangeRates;
        this.speculation = speculation;
        this.currentUser = currentUser;
    }

    private static String currencyOf(Stock stock) {
        return stock.getCurrency() == null || stock.getCurrency().isBlank()
                ? stock.getMarket().getDefaultCurrency() : stock.getCurrency();
    }

    /**
     * A holding's figures in its account's currency (or the stock's own currency when the account
     * has none, or when no exchange rate is available), plus gain/loss converted to USD.
     * Gain/loss is only computed when the average cost is known and the price could be converted.
     */
    private record Metrics(String currency, BigDecimal price, BigDecimal nativeValue, BigDecimal value,
                           BigDecimal averageCost, BigDecimal gain, BigDecimal gainUsd, BigDecimal costUsd) {
    }

    private Metrics metrics(Holding h, Map<String, BigDecimal> ratesUsed) {
        Stock stock = h.getStock();
        String stockCurrency = currencyOf(stock);
        String accountCurrency = h.getTradingAccount().getCurrency();
        BigDecimal stockPrice = stock.getCurrentPrice();
        BigDecimal nativeValue = h.getShares().multiply(stockPrice == null ? BigDecimal.ZERO : stockPrice)
                .setScale(2, RoundingMode.HALF_UP);

        String currency = accountCurrency == null ? stockCurrency : accountCurrency;
        BigDecimal toDisplay = exchangeRates.rate(stockCurrency, currency);
        if (toDisplay == null) {
            // No rate available: show the stock's own currency rather than a wrong number.
            currency = stockCurrency;
            toDisplay = BigDecimal.ONE;
        } else if (ratesUsed != null && !stockCurrency.equals(currency)) {
            ratesUsed.put(stockCurrency + "->" + currency, toDisplay);
        }
        boolean converted = accountCurrency == null || currency.equals(accountCurrency);

        BigDecimal price = stockPrice == null ? null : stockPrice.multiply(toDisplay);
        BigDecimal value = price == null ? BigDecimal.ZERO : h.getShares().multiply(price).setScale(2, RoundingMode.HALF_UP);

        BigDecimal cost = h.getAverageCost();
        BigDecimal gain = null;
        BigDecimal gainUsd = null;
        BigDecimal costUsd = null;
        if (converted && price != null && cost != null && cost.signum() > 0) {
            gain = price.subtract(cost).multiply(h.getShares()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal toUsd = exchangeRates.rate(currency, Currencies.BASE);
            if (toUsd != null) {
                if (ratesUsed != null && !currency.equals(Currencies.BASE)) {
                    ratesUsed.put(currency + "->" + Currencies.BASE, toUsd);
                }
                gainUsd = gain.multiply(toUsd);
                costUsd = cost.multiply(h.getShares()).multiply(toUsd);
            }
        }
        return new Metrics(currency, price, nativeValue, value, cost, gain, gainUsd, costUsd);
    }

    @Transactional(readOnly = true)
    public PortfolioView buildPortfolioView() {
        Long ownerId = currentUser.currentUserId();
        List<TradingAccount> accounts = accountRepository.findAllByOwnerId(ownerId);
        List<Holding> holdings = holdingRepository.findAllByTradingAccount_Owner_Id(ownerId);

        Map<Long, PortfolioRow> rowsByStockId = new LinkedHashMap<>();
        for (Holding holding : holdings) {
            Stock stock = holding.getStock();
            PortfolioRow row = rowsByStockId.computeIfAbsent(stock.getId(),
                    id -> new PortfolioRow(stock.getId(), stock.getSymbol(), stock.getMarket(),
                            stock.getCompanyName(), currencyOf(stock), stock.getCurrentPrice(),
                            stock.getPriceUpdatedAt()));

            Metrics m = metrics(holding, null);
            row.putHolding(holding.getTradingAccount().getId(), holding.getShares(), m.nativeValue(), m.value(),
                    Currencies.symbol(m.currency()), m.averageCost(), m.gainUsd(), m.costUsd());
        }

        List<PortfolioRow> rows = rowsByStockId.values().stream()
                .sorted((a, b) -> a.getSymbol().compareToIgnoreCase(b.getSymbol()))
                .toList();

        // Grand totals of value are kept separate per currency: summing USD, CAD and
        // INR holdings into one number would be meaningless.
        Map<String, BigDecimal> grandTotalsByCurrency = new TreeMap<>();
        BigDecimal totalGain = null;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (PortfolioRow row : rows) {
            grandTotalsByCurrency.merge(row.getCurrency(), row.getTotalValue(), BigDecimal::add);
            if (row.getGainLossUsd() != null) {
                totalGain = (totalGain == null ? BigDecimal.ZERO : totalGain).add(row.getGainLossUsd());
                totalCost = totalCost.add(row.getCostUsd());
            }
        }
        BigDecimal totalPercent = totalGain == null || totalCost.signum() <= 0 ? null
                : totalGain.multiply(BigDecimal.valueOf(100)).divide(totalCost, 1, RoundingMode.HALF_UP);

        return new PortfolioView(accounts, rows, grandTotalsByCurrency, totalGain, totalPercent);
    }

    @Transactional
    public TradingAccount createAccount(String name, String broker, String currency) {
        User owner = currentUser.currentUser();
        String newName = name == null ? "" : name.trim();
        accountRepository.findByOwnerIdAndNameIgnoreCase(owner.getId(), newName).ifPresent(existing -> {
            throw new IllegalArgumentException("An account named \"" + newName + "\" already exists.");
        });
        TradingAccount account = new TradingAccount(newName, broker == null ? null : broker.trim());
        account.setOwner(owner);
        account.setCurrency(Currencies.normalize(currency));
        return accountRepository.save(account);
    }

    private static String usd(BigDecimal amount) {
        return (amount.signum() >= 0 ? "+$" : "-$") + amount.abs().setScale(2, RoundingMode.HALF_UP).toPlainString() + " USD";
    }

    private static String pct(BigDecimal percent) {
        return (percent.signum() > 0 ? "+" : "") + percent.toPlainString() + "%";
    }

    /**
     * Plain-text description of every holding, grouped by account, for use in an LLM prompt.
     * Returns null when there are no holdings. Amounts are in each account's currency, with
     * gain/loss also converted to USD.
     */
    /** True when there is anything to analyze: holdings or speculation stocks. */
    @Transactional(readOnly = true)
    public boolean hasData() {
        Long ownerId = currentUser.currentUserId();
        return !holdingRepository.findAllByTradingAccount_Owner_Id(ownerId).isEmpty() || !speculation.isEmpty();
    }

    @Transactional(readOnly = true)
    public String buildPortfolioSummary() {
        List<Holding> holdings = holdingRepository.findAllByTradingAccount_Owner_Id(currentUser.currentUserId());
        String speculationSection = speculation.buildSummarySection();
        if (holdings.isEmpty() && speculationSection == null) {
            return null;
        }
        Map<String, List<Holding>> byAccount = new TreeMap<>();
        Map<String, BigDecimal> totalsByCurrency = new TreeMap<>();
        Map<String, BigDecimal> ratesUsed = new TreeMap<>();
        Map<String, BigDecimal[]> gainByStock = new TreeMap<>(); // symbol -> {gainUsd, costUsd}
        BigDecimal totalGainUsd = null;
        for (Holding h : holdings) {
            TradingAccount a = h.getTradingAccount();
            String label = a.getName() + (a.getBroker() == null || a.getBroker().isBlank() ? "" : " (" + a.getBroker() + ")")
                    + (a.getCurrency() == null ? "" : " [" + a.getCurrency() + " account]");
            byAccount.computeIfAbsent(label, k -> new java.util.ArrayList<>()).add(h);
        }

        StringBuilder sb = new StringBuilder("Portfolio holdings (amounts are in each account's currency; "
                + "gain/loss is also converted to USD):\n");
        for (Map.Entry<String, List<Holding>> entry : byAccount.entrySet()) {
            sb.append("\nAccount: ").append(entry.getKey()).append('\n');
            entry.getValue().sort((x, y) -> x.getStock().getSymbol().compareToIgnoreCase(y.getStock().getSymbol()));
            for (Holding h : entry.getValue()) {
                Stock s = h.getStock();
                Metrics m = metrics(h, ratesUsed);
                sb.append("- ").append(s.getSymbol()).append(" [").append(s.getMarket().getLabel()).append("] ")
                        .append(s.getCompanyName()).append(": ")
                        .append(h.getShares().stripTrailingZeros().toPlainString()).append(" shares");
                if (m.price() != null) {
                    totalsByCurrency.merge(m.currency(), m.value(), BigDecimal::add);
                    sb.append(", price ").append(m.price().setScale(2, RoundingMode.HALF_UP).toPlainString()).append(' ').append(m.currency())
                            .append(", value ").append(m.value().toPlainString()).append(' ').append(m.currency());
                }
                if (m.averageCost() != null && m.averageCost().signum() > 0) {
                    sb.append(", avg cost ").append(m.averageCost().setScale(2, RoundingMode.HALF_UP).toPlainString())
                            .append(' ').append(m.currency());
                    if (m.gain() != null) {
                        BigDecimal p = m.price().subtract(m.averageCost()).multiply(BigDecimal.valueOf(100))
                                .divide(m.averageCost(), 1, RoundingMode.HALF_UP);
                        sb.append(" (").append(pct(p)).append(" vs cost, ")
                                .append(m.gain().signum() >= 0 ? "gain " : "loss ").append(m.gain().abs().toPlainString())
                                .append(' ').append(m.currency());
                        if (m.gainUsd() != null) {
                            sb.append(" = ").append(usd(m.gainUsd()));
                            gainByStock.merge(s.getSymbol(), new BigDecimal[]{m.gainUsd(), m.costUsd()},
                                    (x, y) -> new BigDecimal[]{x[0].add(y[0]), x[1].add(y[1])});
                            totalGainUsd = (totalGainUsd == null ? BigDecimal.ZERO : totalGainUsd).add(m.gainUsd());
                        }
                        sb.append(')');
                    }
                }
                sb.append('\n');
            }
        }
        if (!gainByStock.isEmpty()) {
            sb.append("\nGain/loss per stock across all accounts, in USD:\n");
            gainByStock.forEach((symbol, g) -> {
                sb.append("- ").append(symbol).append(": ").append(usd(g[0]));
                if (g[1].signum() > 0) {
                    sb.append(" (").append(pct(g[0].multiply(BigDecimal.valueOf(100)).divide(g[1], 1, RoundingMode.HALF_UP))).append(')');
                }
                sb.append('\n');
            });
            sb.append("Total gain/loss: ").append(usd(totalGainUsd)).append('\n');
        }
        sb.append("\nTotal value by currency:\n");
        totalsByCurrency.forEach((cur, total) -> sb.append("- ").append(cur).append(": ").append(total.toPlainString()).append('\n'));
        if (!ratesUsed.isEmpty()) {
            sb.append("\nExchange rates used:\n");
            ratesUsed.forEach((pair, rate) -> sb.append("- 1 ").append(pair.replace("->", " = "))
                    .append(' ').append(rate.setScale(4, RoundingMode.HALF_UP).toPlainString()).append('\n'));
        }
        if (speculationSection != null) {
            sb.append('\n').append(speculationSection);
        }
        return sb.toString();
    }

    private TradingAccount ownedAccount(Long accountId) {
        TradingAccount account = accountRepository.findById(accountId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found."));
        if (account.getOwner() == null || !account.getOwner().getId().equals(currentUser.currentUserId())) {
            throw new IllegalArgumentException("Account not found.");
        }
        return account;
    }

    @Transactional
    public TradingAccount updateAccount(Long accountId, String name, String broker, String currency) {
        TradingAccount account = ownedAccount(accountId);
        String newName = name == null ? "" : name.trim();
        if (newName.isEmpty()) {
            throw new IllegalArgumentException("Account name cannot be empty.");
        }
        accountRepository.findByOwnerIdAndNameIgnoreCase(currentUser.currentUserId(), newName).ifPresent(existing -> {
            if (!existing.getId().equals(accountId)) {
                throw new IllegalArgumentException("An account named \"" + newName + "\" already exists.");
            }
        });
        account.setName(newName);
        account.setBroker(broker == null || broker.isBlank() ? null : broker.trim());
        account.setCurrency(Currencies.normalize(currency));
        return account;
    }

    /** Deletes the account; its holdings are removed via cascade. */
    @Transactional
    public String deleteAccount(Long accountId) {
        TradingAccount account = ownedAccount(accountId);
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
     * Records a purchase of a stock in an account. If the stock isn't known yet it is looked up and
     * created; each purchase is kept as its own lot (date, shares, price), and the holding's total
     * shares and average purchase price are recalculated from all of its lots.
     *
     * @param purchasedOn date of the purchase; null means today
     */
    @Transactional
    public void addShares(Long accountId, String symbolInput, Market market, BigDecimal shares, BigDecimal pricePaid,
                          LocalDate purchasedOn) {
        if (shares == null || shares.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Shares must be greater than zero");
        }
        if (pricePaid != null && pricePaid.signum() < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        if (market == null) {
            throw new IllegalArgumentException("Market must be specified");
        }
        LocalDate date = purchasedOn == null ? LocalDate.now() : purchasedOn;
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Purchase date cannot be in the future");
        }

        TradingAccount account = ownedAccount(accountId);

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

        holding.getLots().add(new PurchaseLot(holding, shares, costBasis.setScale(4, RoundingMode.HALF_UP), date));
        recalculate(holding);
        saveIfNew(holding);
    }

    /**
     * A holding that is already loaded is flushed with its lots when the transaction ends. Calling
     * save() on it would merge it, which copies its new lots and leaves the originals transient.
     */
    private void saveIfNew(Holding holding) {
        if (holding.getId() == null) {
            holdingRepository.save(holding);
        }
    }

    /** Recomputes a holding's total shares and weighted average purchase price from its lots. */
    private static void recalculate(Holding holding) {
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        for (PurchaseLot lot : holding.getLots()) {
            shares = shares.add(lot.getShares());
            cost = cost.add(lot.getShares().multiply(lot.getPrice()));
        }
        holding.setShares(shares);
        holding.setAverageCost(shares.signum() == 0 ? null : cost.divide(shares, 4, RoundingMode.HALF_UP));
    }

    /**
     * After the totals are edited directly (spreadsheet-style), the individual purchases no longer add
     * up, so they are replaced by one lot holding the edited totals, dated as the earliest purchase.
     */
    private static void collapseToSingleLot(Holding holding) {
        LocalDate date = holding.getLots().stream().map(PurchaseLot::getPurchasedOn)
                .min(LocalDate::compareTo).orElse(LocalDate.now());
        holding.getLots().clear();
        BigDecimal price = holding.getAverageCost() == null ? BigDecimal.ZERO : holding.getAverageCost();
        holding.getLots().add(new PurchaseLot(holding, holding.getShares(), price, date));
    }

    /** One purchase, as shown in the purchase history. */
    public record LotView(Long id, LocalDate purchasedOn, BigDecimal shares, BigDecimal price) {
    }

    /** All purchases of one stock within one of the user's accounts. */
    public record AccountLots(Long accountId, String accountName, String currencySymbol, BigDecimal totalShares,
                              BigDecimal averageCost, List<LotView> lots) {
    }

    /** The signed-in user's purchase history for a stock, per account, oldest purchase first. */
    @Transactional(readOnly = true)
    public List<AccountLots> purchaseHistory(Long stockId) {
        Map<Long, List<PurchaseLot>> byAccount = new LinkedHashMap<>();
        for (PurchaseLot lot : lotRepository.findAllByHolding_TradingAccount_Owner_IdAndHolding_Stock_IdOrderByPurchasedOnAscIdAsc(
                currentUser.currentUserId(), stockId)) {
            byAccount.computeIfAbsent(lot.getHolding().getTradingAccount().getId(), k -> new java.util.ArrayList<>()).add(lot);
        }
        List<AccountLots> result = new java.util.ArrayList<>();
        for (List<PurchaseLot> lots : byAccount.values()) {
            Holding holding = lots.get(0).getHolding();
            TradingAccount account = holding.getTradingAccount();
            String currency = account.getCurrency() != null && !account.getCurrency().isBlank()
                    ? account.getCurrency() : currencyOf(holding.getStock());
            result.add(new AccountLots(account.getId(), account.getName(), Currencies.symbol(currency),
                    holding.getShares(), holding.getAverageCost(),
                    lots.stream().map(l -> new LotView(l.getId(), l.getPurchasedOn(), l.getShares(), l.getPrice())).toList()));
        }
        return result;
    }

    private static void validatePurchase(BigDecimal shares, BigDecimal price, LocalDate date) {
        if (shares == null || shares.signum() <= 0) {
            throw new IllegalArgumentException("Shares must be greater than zero");
        }
        if (price == null || price.signum() < 0) {
            throw new IllegalArgumentException("Price cannot be negative");
        }
        if (date == null) {
            throw new IllegalArgumentException("Purchase date is required");
        }
        if (date.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Purchase date cannot be in the future");
        }
    }

    /** Changes one purchase's date, shares and price; the holding's totals are recalculated. */
    @Transactional
    public void updatePurchase(Long lotId, LocalDate purchasedOn, BigDecimal shares, BigDecimal price) {
        validatePurchase(shares, price, purchasedOn);
        PurchaseLot lot = lotRepository.findByIdAndHolding_TradingAccount_Owner_Id(lotId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found."));
        lot.setPurchasedOn(purchasedOn);
        lot.setShares(shares);
        lot.setPrice(price.setScale(4, RoundingMode.HALF_UP));
        recalculate(lot.getHolding());
    }

    /** Adds a purchase to a stock the account already holds. */
    @Transactional
    public void addPurchase(Long accountId, Long stockId, LocalDate purchasedOn, BigDecimal shares, BigDecimal price) {
        validatePurchase(shares, price, purchasedOn);
        ownedAccount(accountId);
        Holding holding = holdingRepository.findByTradingAccountIdAndStockId(accountId, stockId)
                .orElseThrow(() -> new IllegalArgumentException("This account does not hold that stock."));
        holding.getLots().add(new PurchaseLot(holding, shares, price.setScale(4, RoundingMode.HALF_UP), purchasedOn));
        recalculate(holding);
    }

    /** Removes one purchase; the holding's totals are recalculated, and the holding goes if nothing is left. */
    @Transactional
    public void deletePurchase(Long lotId) {
        PurchaseLot lot = lotRepository.findByIdAndHolding_TradingAccount_Owner_Id(lotId, currentUser.currentUserId())
                .orElseThrow(() -> new IllegalArgumentException("Purchase not found."));
        Holding holding = lot.getHolding();
        holding.getLots().remove(lot);
        if (holding.getLots().isEmpty()) {
            holdingRepository.delete(holding);
            return;
        }
        recalculate(holding);
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
        TradingAccount account = ownedAccount(accountId);
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
        collapseToSingleLot(holding);
        saveIfNew(holding);
    }

    /** Sets the average purchase price of an account's existing position; blank clears it. */
    @Transactional
    public void setAverageCost(Long accountId, Long stockId, BigDecimal averageCost) {
        if (averageCost != null && averageCost.signum() < 0) {
            throw new IllegalArgumentException("Average cost cannot be negative");
        }
        ownedAccount(accountId);
        Holding holding = holdingRepository.findByTradingAccountIdAndStockId(accountId, stockId)
                .orElseThrow(() -> new IllegalArgumentException("Add shares to this account before setting an average cost."));
        holding.setAverageCost(averageCost == null ? null : averageCost.setScale(4, RoundingMode.HALF_UP));
        collapseToSingleLot(holding);
        saveIfNew(holding);
    }

    /**
     * Refreshes the current market price for every stock currently held.
     * Failures for individual symbols are logged and skipped so one bad
     * symbol doesn't block the rest of the refresh.
     */
    @Transactional
    public int refreshAllPrices() {
        exchangeRates.invalidate();
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
