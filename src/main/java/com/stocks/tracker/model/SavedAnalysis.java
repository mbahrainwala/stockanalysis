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

    /** Nullable only so existing rows can be backfilled on upgrade; always set by application code. */
    @ManyToOne(optional = true, fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private User owner;

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

    public SavedAnalysis(User owner, LocalDateTime createdAt, String model, long durationMs, String prompt, String response) {
        this.owner = owner;
        this.createdAt = createdAt;
        this.model = model;
        this.durationMs = durationMs;
        this.prompt = prompt;
        this.response = response;
    }

    public Long getId() {
        return id;
    }

    public User getOwner() {
        return owner;
    }

    public void setOwner(User owner) {
        this.owner = owner;
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
