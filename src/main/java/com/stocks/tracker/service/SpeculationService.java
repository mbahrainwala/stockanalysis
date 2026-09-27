package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocks.tracker.dto.PricePoint;
import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.dto.SpeculationRow;
import com.stocks.tracker.model.Currencies;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.SpeculationEntry;
import com.stocks.tracker.model.SpeculationEntry.Source;
import com.stocks.tracker.model.Stock;
import com.stocks.tracker.repository.HoldingRepository;
import com.stocks.tracker.repository.SpeculationEntryRepository;
import com.stocks.tracker.repository.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single Speculation list: stocks being considered for purchase, tracked from the day they
 * were added. Entries are added by the user or by the AI (tagged as such) so recommendations
 * can be judged against how the price actually moved.
 */
@Service
public class SpeculationService {

    private static final Logger log = LoggerFactory.getLogger(SpeculationService.class);
    public static final int MAX_AI_PICKS = 3;
    private static final Pattern PICKS_BLOCK = Pattern.compile("```\\s*(?:speculation|json)\\s*\\n(\\[.*?])\\s*```",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern SYMBOL = Pattern.compile("[A-Za-z0-9.\\-]{1,12}");

    /** Outcome of reading AI picks out of a response: the text to show, plus what happened. */
    public record AiPickResult(String response, List<String> added, List<String> skipped) {
    }

    private final SpeculationEntryRepository entries;
    private final StockRepository stocks;
    private final HoldingRepository holdings;
    private final MarketDataService marketData;
    private final ObjectMapper mapper = new ObjectMapper();

    public SpeculationService(SpeculationEntryRepository entries, StockRepository stocks,
                              HoldingRepository holdings, MarketDataService marketData) {
        this.entries = entries;
        this.stocks = stocks;
        this.holdings = holdings;
        this.marketData = marketData;
    }

    @Transactional(readOnly = true)
    public List<SpeculationRow> listRows() {
        LocalDateTime now = LocalDateTime.now();
        return entries.findAllByOrderByAddedAtDesc().stream().map(e -> {
            Stock s = e.getStock();
            BigDecimal price = s.getCurrentPrice();
            BigDecimal change = price == null || e.getAddedPrice().signum() <= 0 ? null
                    : price.subtract(e.getAddedPrice()).multiply(BigDecimal.valueOf(100))
                    .divide(e.getAddedPrice(), 1, RoundingMode.HALF_UP);
            String currency = s.getCurrency() == null ? s.getMarket().getDefaultCurrency() : s.getCurrency();
            return new SpeculationRow(e.getId(), s.getSymbol(), s.getMarket(), s.getCompanyName(),
                    Currencies.symbol(currency), e.getAddedAt(), Duration.between(e.getAddedAt(), now).toDays(),
                    e.getAddedPrice(), price, change, e.getAddedBy(), e.getNote());
        }).toList();
    }

    public SpeculationRow findRow(Long id) {
        return listRows().stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Entry not found."));
    }

    /** Looks the symbol up live (which also validates it), then records it at today's price. */
    public SpeculationEntry add(String symbolInput, Market market, String note, Source source) {
        QuoteResult quote = marketData.fetchQuote(symbolInput, market);
        Stock stock = stocks.findBySymbolIgnoreCaseAndMarket(quote.symbol(), market).orElseGet(() -> {
            Stock s = new Stock(quote.symbol(), quote.market(), quote.companyName());
            s.setCurrency(quote.currency());
            return s;
        });
        stock.setCurrentPrice(quote.price());
        stock.setCurrency(quote.currency());
        stock.setPriceUpdatedAt(LocalDateTime.now());
        stock = stocks.save(stock);
        if (entries.existsByStockId(stock.getId())) {
            throw new IllegalArgumentException(stock.getSymbol() + " is already on the speculation list.");
        }
        String cleanNote = note == null || note.isBlank() ? null : note.strip();
        if (cleanNote != null && cleanNote.length() > 1000) {
            cleanNote = cleanNote.substring(0, 1000);
        }
        return entries.save(new SpeculationEntry(stock, LocalDateTime.now(), quote.price(), source, cleanNote));
    }

    public void delete(Long id) {
        if (!entries.existsById(id)) {
            throw new IllegalArgumentException("Entry not found.");
        }
        entries.deleteById(id);
    }

    @Transactional(readOnly = true)
    public List<PricePoint> history(Long id, String range) {
        SpeculationEntry e = entries.findById(id).orElseThrow(() -> new IllegalArgumentException("Entry not found."));
        return marketData.fetchHistory(e.getStock().getSymbol(), e.getStock().getMarket(), range);
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return entries.count() == 0;
    }

    /** Text section for the LLM prompt, or null when the list is empty. */
    @Transactional(readOnly = true)
    public String buildSummarySection() {
        List<SpeculationRow> rows = listRows();
        if (rows.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Speculation watchlist (stocks being considered for purchase, not owned; "
                + "prices in each stock's own currency):\n");
        for (SpeculationRow r : rows) {
            sb.append("- ").append(r.symbol()).append(" [").append(r.market().getLabel()).append("] ")
                    .append(r.companyName()).append(": added ").append(r.addedAt().toLocalDate())
                    .append(r.isAi() ? " by AI" : " by the user")
                    .append(" at ").append(r.addedPrice().setScale(2, RoundingMode.HALF_UP).toPlainString());
            if (r.currentPrice() != null) {
                sb.append(", now ").append(r.currentPrice().setScale(2, RoundingMode.HALF_UP).toPlainString());
            }
            if (r.changePercent() != null) {
                sb.append(" (").append(r.changePercent().signum() > 0 ? "+" : "").append(r.changePercent().toPlainString())
                        .append("% in ").append(r.daysHeld()).append(" days)");
            }
            if (r.note() != null) {
                sb.append(" - note: ").append(r.note().replace('\n', ' '));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Reads the machine-readable picks block the analysis prompt asks the model to append, adds each
     * valid pick to the list tagged as AI, and returns the response with that block replaced by a
     * short note. Every ticker is verified with a live quote, so invented symbols are skipped.
     */
    public AiPickResult addAiPicks(String response) {
        if (response == null) {
            return new AiPickResult(null, List.of(), List.of());
        }
        Matcher m = PICKS_BLOCK.matcher(response);
        int start = -1;
        int end = -1;
        String json = null;
        while (m.find()) {
            start = m.start();
            end = m.end();
            json = m.group(1);
        }
        if (json == null) {
            return new AiPickResult(response, List.of(), List.of());
        }
        JsonNode picks;
        try {
            picks = mapper.readTree(json);
        } catch (Exception e) {
            log.warn("Could not parse AI speculation picks: {}", e.getMessage());
            return new AiPickResult(response, List.of(), List.of());
        }

        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int considered = 0;
        for (JsonNode pick : picks) {
            if (considered++ >= MAX_AI_PICKS) {
                break;
            }
            String symbol = pick.path("symbol").asText("").trim().toUpperCase();
            if (!SYMBOL.matcher(symbol).matches()) {
                continue;
            }
            Market market;
            try {
                market = Market.valueOf(pick.path("market").asText("US").trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                skipped.add(symbol + " (unknown market)");
                continue;
            }
            try {
                var existing = stocks.findBySymbolIgnoreCaseAndMarket(symbol, market);
                if (existing.isPresent() && holdings.existsByStockId(existing.get().getId())) {
                    skipped.add(symbol + " (already owned)");
                    continue;
                }
                add(symbol, market, pick.path("reason").asText(""), Source.AI);
                added.add(symbol + " (" + market.getLabel() + ")");
            } catch (StockLookupException e) {
                skipped.add(symbol + " (not found)");
            } catch (IllegalArgumentException e) {
                skipped.add(symbol + " (already on the list)");
            } catch (Exception e) {
                log.warn("Could not add AI pick {}: {}", symbol, e.getMessage());
                skipped.add(symbol + " (error)");
            }
        }

        StringBuilder text = new StringBuilder(response.substring(0, start).stripTrailing());
        String tail = response.substring(end).strip();
        if (!tail.isEmpty()) {
            text.append("\n\n").append(tail);
        }
        if (!added.isEmpty()) {
            text.append("\n\n**Added to Speculation by AI:** ").append(String.join(", ", added));
        }
        if (!skipped.isEmpty()) {
            text.append("\n\n*Not added:* ").append(String.join(", ", skipped));
        }
        return new AiPickResult(text.toString(), added, skipped);
    }
}
