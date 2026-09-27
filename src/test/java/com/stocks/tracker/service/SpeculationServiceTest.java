package com.stocks.tracker.service;

import com.stocks.tracker.dto.QuoteResult;
import com.stocks.tracker.model.Market;
import com.stocks.tracker.model.SpeculationEntry;
import com.stocks.tracker.model.Stock;
import com.stocks.tracker.repository.HoldingRepository;
import com.stocks.tracker.repository.SpeculationEntryRepository;
import com.stocks.tracker.repository.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SpeculationServiceTest {

    private SpeculationEntryRepository entries;
    private StockRepository stocks;
    private HoldingRepository holdings;
    private MarketDataService marketData;
    private SpeculationService service;

    @BeforeEach
    void setUp() {
        entries = mock(SpeculationEntryRepository.class);
        stocks = mock(StockRepository.class);
        holdings = mock(HoldingRepository.class);
        marketData = mock(MarketDataService.class);
        service = new SpeculationService(entries, stocks, holdings, marketData);
        when(stocks.findBySymbolIgnoreCaseAndMarket(any(), any())).thenReturn(Optional.empty());
        when(stocks.save(any(Stock.class))).thenAnswer(i -> i.getArgument(0));
        when(entries.save(any(SpeculationEntry.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void quote(String symbol, Market market, String price) {
        when(marketData.fetchQuote(symbol, market))
                .thenReturn(new QuoteResult(symbol, market, symbol + " Inc", new BigDecimal(price), "USD"));
    }

    @Test
    void addsValidPicksTaggedAsAiAndStripsTheBlock() {
        quote("NVDA", Market.US, "120.50");
        when(marketData.fetchQuote(eq("FAKE"), any())).thenThrow(new StockLookupException("Symbol not found"));

        String reply = "Some analysis.\n\n```speculation\n"
                + "[{\"symbol\":\"nvda\",\"market\":\"US\",\"reason\":\"AI demand\"},"
                + "{\"symbol\":\"FAKE\",\"market\":\"US\",\"reason\":\"made up\"}]\n```";
        SpeculationService.AiPickResult result = service.addAiPicks(reply);

        ArgumentCaptor<SpeculationEntry> saved = ArgumentCaptor.forClass(SpeculationEntry.class);
        verify(entries).save(saved.capture());
        assertEquals(SpeculationEntry.Source.AI, saved.getValue().getAddedBy());
        assertEquals("AI demand", saved.getValue().getNote());
        assertEquals(new BigDecimal("120.50"), saved.getValue().getAddedPrice());
        assertEquals(1, result.added().size());
        assertEquals(1, result.skipped().size());
        assertFalse(result.response().contains("```"));
        assertTrue(result.response().startsWith("Some analysis."));
        assertTrue(result.response().contains("Added to Speculation by AI"));
        assertTrue(result.response().contains("FAKE (not found)"));
    }

    @Test
    void skipsStocksAlreadyOwned() {
        Stock owned = new Stock("AAPL", Market.US, "Apple");
        when(stocks.findBySymbolIgnoreCaseAndMarket("AAPL", Market.US)).thenReturn(Optional.of(owned));
        when(holdings.existsByStockId(owned.getId())).thenReturn(true);

        SpeculationService.AiPickResult result = service.addAiPicks(
                "```speculation\n[{\"symbol\":\"AAPL\",\"market\":\"US\",\"reason\":\"x\"}]\n```");

        verify(entries, never()).save(any(SpeculationEntry.class));
        assertTrue(result.added().isEmpty());
        assertTrue(result.response().contains("AAPL (already owned)"));
    }

    @Test
    void limitsPicksAndIgnoresMalformedBlocks() {
        quote("A", Market.US, "1");
        quote("B", Market.US, "1");
        quote("C", Market.US, "1");
        quote("D", Market.US, "1");
        String four = "```speculation\n[{\"symbol\":\"A\"},{\"symbol\":\"B\"},{\"symbol\":\"C\"},{\"symbol\":\"D\"}]\n```";
        assertEquals(SpeculationService.MAX_AI_PICKS, service.addAiPicks(four).added().size());

        String broken = "text\n```speculation\n[not json]\n```";
        assertEquals(broken, service.addAiPicks(broken).response());
        assertEquals("plain reply", service.addAiPicks("plain reply").response());
    }
}
