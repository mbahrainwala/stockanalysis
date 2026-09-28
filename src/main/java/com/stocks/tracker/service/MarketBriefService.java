package com.stocks.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stocks.tracker.dto.SpeculationRow;
import com.stocks.tracker.model.Holding;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.Stock;
import com.stocks.tracker.repository.HoldingRepository;
import com.stocks.tracker.security.CurrentUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Gathers current market data from Yahoo Finance and condenses it into a text "brief" for the model:
 * a quote snapshot for every stock the user holds or watches, analyst views, new-idea candidates
 * from Yahoo's screeners, and recent headlines. The brief is assembled in priority order and cut to
 * fit a token budget so the whole prompt stays inside the model's context window.
 */
@Service
public class MarketBriefService {

    private static final Logger log = LoggerFactory.getLogger(MarketBriefService.class);
    private static final int MAX_DEEP_STOCKS = 25;
    private static final int MAX_CANDIDATES = 15;
    private static final int MIN_USEFUL_BUDGET = 1500;
    private static final List<String> SCREENS = List.of("undervalued_growth_stocks", "growth_technology_stocks",
            "undervalued_large_caps", "day_gainers");
    private static final Pattern US_TICKER = Pattern.compile("[A-Z]{1,5}");

    /** A titled block of lines; lines are dropped from the end when the budget runs out. */
    record Section(String title, String intro, List<String> lines) {
    }

    /** A stock the brief is about. */
    private record Ref(String symbol, Market market, String name, boolean watched) {
        String yahooSymbol() {
            return market.toYahooSymbol(symbol);
        }
    }

    private record Snapshot(String symbol, String name, String currency, Double price, Double dayPct, Double low52,
                            Double high52, Double avg50, Double avg200, Double pe, Double forwardPe, Double marketCap,
                            Double yearPct, String analystRating) {
    }

    private final YahooSession yahoo;
    private final ExternalRatingService ratings;
    private final ExchangeRateService exchangeRates;
    private final HoldingRepository holdings;
    private final SpeculationService speculation;
    private final AiContextProperties limits;
    private final CurrentUserService currentUser;

    public MarketBriefService(YahooSession yahoo, ExternalRatingService ratings, ExchangeRateService exchangeRates,
                              HoldingRepository holdings, SpeculationService speculation, AiContextProperties limits,
                              CurrentUserService currentUser) {
        this.yahoo = yahoo;
        this.ratings = ratings;
        this.exchangeRates = exchangeRates;
        this.holdings = holdings;
        this.speculation = speculation;
        this.limits = limits;
        this.currentUser = currentUser;
    }

    /**
     * Builds the brief, or an empty string when there is no room or no data.
     *
     * @param tokensAlreadyUsed estimated size of the rest of the prompt (instructions, portfolio summary, chat)
     * @param includeCandidates whether to add new-idea candidates from Yahoo's screeners
     */
    @Transactional(readOnly = true)
    public String buildBrief(int tokensAlreadyUsed, boolean includeCandidates) {
        int budget = Math.min(limits.getBriefMaxTokens(), limits.getInputBudgetTokens() - tokensAlreadyUsed);
        if (budget < MIN_USEFUL_BUDGET) {
            log.warn("No room for market data in the prompt ({} tokens left of the context window)", budget);
            return "";
        }

        List<Ref> universe = universe();
        Set<String> known = new LinkedHashSet<>();
        universe.forEach(r -> known.add(r.yahooSymbol()));

        Map<String, Snapshot> snapshots = new LinkedHashMap<>(fetchSnapshots(universe.stream().map(Ref::yahooSymbol).toList()));

        Map<String, List<String>> candidateScreens = includeCandidates ? findCandidates(known) : Map.of();
        if (!candidateScreens.isEmpty()) {
            snapshots.putAll(fetchSnapshots(new ArrayList<>(candidateScreens.keySet())));
        }

        List<Ref> deep = universe.subList(0, Math.min(universe.size(), MAX_DEEP_STOCKS));
        Map<String, ExternalRatingService.Rating> details = fetchDetails(deep);

        List<Section> sections = new ArrayList<>();
        sections.add(speculationSnapshotSection(universe, snapshots));
        sections.add(holdingsSnapshotSection(universe, snapshots));
        sections.add(analystSection(deep, details, snapshots));
        if (!candidateScreens.isEmpty()) {
            sections.add(candidateSection(candidateScreens, snapshots));
        }
        sections.add(newsSection(deep, details));
        return assemble(sections, budget);
    }

    // ---- Which stocks ----

    /** Watchlist first, then holdings from largest to smallest position (in USD). */
    private List<Ref> universe() {
        Map<String, Ref> byKey = new LinkedHashMap<>();
        for (SpeculationRow r : speculation.listRows()) {
            byKey.putIfAbsent(r.symbol() + "|" + r.market(), new Ref(r.symbol(), r.market(), r.companyName(), true));
        }
        Map<Long, BigDecimal> valueByStock = new LinkedHashMap<>();
        Map<Long, Stock> stocks = new LinkedHashMap<>();
        for (Holding h : holdings.findAllByTradingAccount_Owner_Id(currentUser.currentUserId())) {
            Stock s = h.getStock();
            BigDecimal price = s.getCurrentPrice() == null ? BigDecimal.ZERO : s.getCurrentPrice();
            String currency = s.getCurrency() == null ? s.getMarket().getDefaultCurrency() : s.getCurrency();
            BigDecimal fx = exchangeRates.rate(currency, "USD");
            BigDecimal usd = h.getShares().multiply(price).multiply(fx == null ? BigDecimal.ONE : fx);
            valueByStock.merge(s.getId(), usd, BigDecimal::add);
            stocks.put(s.getId(), s);
        }
        stocks.values().stream()
                .sorted(Comparator.comparing((Stock s) -> valueByStock.get(s.getId())).reversed())
                .forEach(s -> byKey.putIfAbsent(s.getSymbol() + "|" + s.getMarket(),
                        new Ref(s.getSymbol(), s.getMarket(), s.getCompanyName(), false)));
        return new ArrayList<>(byKey.values());
    }

    /** New ideas from Yahoo's predefined screeners, round-robin across screens; symbol -> screens it appeared in. */
    private Map<String, List<String>> findCandidates(Set<String> known) {
        List<List<String>> perScreen = new ArrayList<>();
        Map<String, List<String>> screensBySymbol = new LinkedHashMap<>();
        for (String screen : SCREENS) {
            List<String> symbols = new ArrayList<>();
            try {
                JsonNode root = yahoo.getJson("https://query1.finance.yahoo.com/v1/finance/screener/predefined/saved?scrIds="
                        + screen + "&count=10", true);
                JsonNode quotes = root == null ? null : root.path("finance").path("result").path(0).path("quotes");
                if (quotes != null) {
                    for (JsonNode q : quotes) {
                        String sym = q.path("symbol").asText("");
                        if (US_TICKER.matcher(sym).matches() && !known.contains(sym)) {
                            symbols.add(sym);
                            screensBySymbol.computeIfAbsent(sym, k -> new ArrayList<>()).add(screen.replace('_', ' '));
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Screener {} failed: {}", screen, e.getMessage());
            }
            perScreen.add(symbols);
        }
        Map<String, List<String>> picked = new LinkedHashMap<>();
        for (int i = 0; picked.size() < MAX_CANDIDATES; i++) {
            boolean any = false;
            for (List<String> symbols : perScreen) {
                if (i < symbols.size()) {
                    any = true;
                    if (picked.size() < MAX_CANDIDATES) {
                        picked.putIfAbsent(symbols.get(i), screensBySymbol.get(symbols.get(i)));
                    }
                }
            }
            if (!any) {
                break;
            }
        }
        return picked;
    }

    // ---- Fetching ----

    private Map<String, Snapshot> fetchSnapshots(List<String> yahooSymbols) {
        Map<String, Snapshot> out = new LinkedHashMap<>();
        for (int i = 0; i < yahooSymbols.size(); i += 40) {
            List<String> chunk = yahooSymbols.subList(i, Math.min(yahooSymbols.size(), i + 40));
            try {
                JsonNode root = yahoo.getJson("https://query1.finance.yahoo.com/v7/finance/quote?symbols="
                        + java.net.URLEncoder.encode(String.join(",", chunk), java.nio.charset.StandardCharsets.UTF_8), true);
                if (root == null) {
                    continue;
                }
                for (JsonNode q : root.path("quoteResponse").path("result")) {
                    String sym = q.path("symbol").asText("");
                    out.put(sym, new Snapshot(sym, q.path("shortName").asText(q.path("longName").asText("")),
                            q.path("currency").asText(""), num(q, "regularMarketPrice"), num(q, "regularMarketChangePercent"),
                            num(q, "fiftyTwoWeekLow"), num(q, "fiftyTwoWeekHigh"), num(q, "fiftyDayAverage"),
                            num(q, "twoHundredDayAverage"), num(q, "trailingPE"), num(q, "forwardPE"), num(q, "marketCap"),
                            num(q, "fiftyTwoWeekChangePercent"), q.hasNonNull("averageAnalystRating")
                            ? q.get("averageAnalystRating").asText() : null));
                }
            } catch (Exception e) {
                log.warn("Quote snapshot fetch failed: {}", e.getMessage());
            }
        }
        return out;
    }

    /** Analyst data and news for each stock, fetched in parallel (results are cached by the rating service). */
    private Map<String, ExternalRatingService.Rating> fetchDetails(List<Ref> refs) {
        Map<String, ExternalRatingService.Rating> out = new LinkedHashMap<>();
        if (refs.isEmpty()) {
            return out;
        }
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<ExternalRatingService.Rating>> tasks = refs.stream()
                    .<Callable<ExternalRatingService.Rating>>map(r -> () -> ratings.lookup(r.symbol(), r.market())).toList();
            List<Future<ExternalRatingService.Rating>> futures = pool.invokeAll(tasks, 60, TimeUnit.SECONDS);
            for (int i = 0; i < refs.size(); i++) {
                try {
                    out.put(refs.get(i).yahooSymbol(), futures.get(i).get());
                } catch (Exception e) {
                    log.warn("No analyst data for {}: {}", refs.get(i).symbol(), e.getMessage());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }

    private static Double num(JsonNode n, String field) {
        return n.hasNonNull(field) && n.get(field).isNumber() ? n.get(field).asDouble() : null;
    }

    // ---- Sections ----

    private Section speculationSnapshotSection(List<Ref> universe, Map<String, Snapshot> snapshots) {
        List<String> lines = new ArrayList<>();
        for (Ref r : universe) {
            if (!r.watched()) {
                continue;
            }
            Snapshot s = snapshots.get(r.yahooSymbol());
            lines.add("- " + r.symbol() + " (" + r.name() + "): " + (s == null ? "no quote data" : describe(s)));
        }
        return new Section("Market snapshot - Speculation watchlist",
                "Live Yahoo Finance data, in each stock's own currency, for stocks the user is considering buying. "
                        + "These are NOT owned and are not part of the portfolio - judge them as speculative candidates, "
                        + "not as holdings.", lines);
    }

    private Section holdingsSnapshotSection(List<Ref> universe, Map<String, Snapshot> snapshots) {
        List<String> lines = new ArrayList<>();
        for (Ref r : universe) {
            if (r.watched()) {
                continue;
            }
            Snapshot s = snapshots.get(r.yahooSymbol());
            lines.add("- " + r.symbol() + " (" + r.name() + "): " + (s == null ? "no quote data" : describe(s)));
        }
        return new Section("Market snapshot - portfolio holdings",
                "Live Yahoo Finance data, in each stock's own currency. Ordered by position size.", lines);
    }

    private Section analystSection(List<Ref> deep, Map<String, ExternalRatingService.Rating> details,
                                   Map<String, Snapshot> snapshots) {
        List<String> lines = new ArrayList<>();
        for (Ref r : deep) {
            ExternalRatingService.Rating rt = details.get(r.yahooSymbol());
            if (rt == null || rt.consensusKey() == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder("- ").append(r.symbol()).append(": consensus ").append(rt.consensus());
            ExternalRatingService.Counts c = rt.counts();
            if (c != null) {
                sb.append(" (").append(c.strongBuy()).append(" strong buy/").append(c.buy()).append(" buy/").append(c.hold())
                        .append(" hold/").append(c.sell()).append(" sell/").append(c.strongSell()).append(" strong sell)");
            }
            ExternalRatingService.Targets t = rt.targets();
            Snapshot snap = snapshots.get(r.yahooSymbol());
            if (t != null && t.mean() != null) {
                sb.append(", avg target ").append(f(t.mean()));
                if (snap != null && snap.price() != null && snap.price() > 0) {
                    sb.append(" (").append(signed((t.mean() - snap.price()) / snap.price() * 100)).append("% vs price)");
                }
                if (t.low() != null && t.high() != null) {
                    sb.append(", range ").append(f(t.low())).append("-").append(f(t.high()));
                }
            }
            List<String> acts = new ArrayList<>();
            for (ExternalRatingService.Action a : rt.actions().stream().limit(3).toList()) {
                acts.add(a.date() + " " + a.firm() + " " + a.action().toLowerCase() + " " + a.toGrade()
                        + (a.priceTarget() == null ? "" : " PT " + f(a.priceTarget())));
            }
            if (!acts.isEmpty()) {
                sb.append("; recent: ").append(String.join("; ", acts));
            }
            lines.add(sb.toString());
        }
        return new Section("Analyst views", "Wall Street consensus, price targets and recent rating changes.", lines);
    }

    private Section candidateSection(Map<String, List<String>> candidates, Map<String, Snapshot> snapshots) {
        List<String> lines = new ArrayList<>();
        candidates.forEach((sym, screens) -> {
            Snapshot s = snapshots.get(sym);
            if (s != null) {
                lines.add("- " + sym + " (" + s.name() + ") [US; found by: " + String.join(", ", screens) + "]: " + describe(s));
            }
        });
        return new Section("New idea candidates (not owned or watched)",
                "From Yahoo Finance screeners. You may pick from these for the Speculation list, or use other stocks you are "
                        + "confident in. These are leads, not recommendations.", lines);
    }

    private Section newsSection(List<Ref> deep, Map<String, ExternalRatingService.Rating> details) {
        List<String> lines = new ArrayList<>();
        for (Ref r : deep) {
            ExternalRatingService.Rating rt = details.get(r.yahooSymbol());
            if (rt == null || rt.news().isEmpty()) {
                continue;
            }
            List<String> items = rt.news().stream().limit(3)
                    .map(n -> "\"" + n.title() + "\" (" + n.publisher() + (n.date().isEmpty() ? "" : ", " + n.date()) + ")").toList();
            lines.add("- " + r.symbol() + ": " + String.join("; ", items));
        }
        return new Section("Recent headlines", "Latest news tagged with each stock.", lines);
    }

    // ---- Formatting ----

    private static String describe(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        if (s.price() != null) {
            sb.append(f(s.price())).append(' ').append(s.currency());
        }
        if (s.dayPct() != null) {
            sb.append(", day ").append(signed(s.dayPct())).append('%');
        }
        if (s.low52() != null && s.high52() != null && s.high52() > 0 && s.price() != null) {
            sb.append(", 52w ").append(f(s.low52())).append('-').append(f(s.high52()))
                    .append(" (").append(signed((s.price() - s.high52()) / s.high52() * 100)).append("% from high)");
        }
        if (s.yearPct() != null) {
            sb.append(", 1y ").append(signed(s.yearPct())).append('%');
        }
        if (s.avg50() != null && s.avg200() != null) {
            sb.append(", 50d avg ").append(f(s.avg50())).append(", 200d avg ").append(f(s.avg200()));
        }
        if (s.pe() != null) {
            sb.append(", P/E ").append(f(s.pe()));
        }
        if (s.forwardPe() != null) {
            sb.append(", fwd P/E ").append(f(s.forwardPe()));
        }
        if (s.marketCap() != null) {
            sb.append(", mkt cap ").append(big(s.marketCap()));
        }
        if (s.analystRating() != null) {
            sb.append(", analysts ").append(s.analystRating());
        }
        return sb.toString();
    }

    private static String f(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String signed(double v) {
        return (v > 0 ? "+" : "") + BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    private static String big(double v) {
        if (v >= 1e12) {
            return f(v / 1e12) + "T";
        }
        if (v >= 1e9) {
            return f(v / 1e9) + "B";
        }
        return f(v / 1e6) + "M";
    }

    // ---- Budget ----

    /**
     * Joins sections in the given (priority) order until the token budget is used up. A section that
     * doesn't fit whole is cut at a line boundary, later sections may be dropped, and a closing note
     * tells the model what was left out so it doesn't assume the data is complete.
     */
    static String assemble(List<Section> sections, int budgetTokens) {
        StringBuilder out = new StringBuilder();
        List<String> omitted = new ArrayList<>();
        int remaining = budgetTokens - TokenEstimator.estimate("\n\nNote: data omitted to fit the context window: \n") - 60;
        for (Section s : sections) {
            if (s.lines().isEmpty()) {
                continue;
            }
            String header = "## " + s.title() + "\n" + s.intro() + "\n";
            int cost = TokenEstimator.estimate(header);
            if (cost + TokenEstimator.estimate(s.lines().get(0) + "\n") > remaining) {
                omitted.add(s.title());
                continue;
            }
            StringBuilder block = new StringBuilder(header);
            remaining -= cost;
            int kept = 0;
            for (String line : s.lines()) {
                int lineCost = TokenEstimator.estimate(line + "\n");
                if (lineCost > remaining) {
                    break;
                }
                block.append(line).append('\n');
                remaining -= lineCost;
                kept++;
            }
            out.append(block).append('\n');
            if (kept < s.lines().size()) {
                omitted.add((s.lines().size() - kept) + " more line(s) of " + s.title());
            }
        }
        if (!omitted.isEmpty()) {
            out.append("Note: data omitted to fit the context window: ").append(String.join("; ", omitted)).append('\n');
        }
        return out.toString().stripTrailing();
    }
}
