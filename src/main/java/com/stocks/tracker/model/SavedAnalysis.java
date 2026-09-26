package com.stocks.tracker.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/** A completed portfolio analysis, kept for future reference. */
@Entity
@Table(name = "saved_analysis")
public class SavedAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private String model;

    private long durationMs;

    /** The portfolio data and instructions that were sent to the model. */
    @Lob
    @Column(nullable = false)
    private String prompt;

    @Lob
    @Column(nullable = false)
    private String response;

    public SavedAnalysis() {
    }

    public SavedAnalysis(LocalDateTime createdAt, String model, long durationMs, String prompt, String response) {
        this.createdAt = createdAt;
        this.model = model;
        this.durationMs = durationMs;
        this.prompt = prompt;
        this.response = response;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public String getModel() {
        return model;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getResponse() {
        return response;
    }
}
