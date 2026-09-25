package com.stocks.tracker.service;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs portfolio analyses and AI test prompts in the background. Local models can take many minutes, so the
 * HTTP request that starts an analysis returns immediately and the page polls for the
 * result; no HTTP request is held open and the model call itself has no timeout.
 * Only one model call runs at a time so a local model isn't asked to do several at once.
 */
@Service
public class AnalysisJobService {

    private static final int MAX_JOBS_KEPT = 20;

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final String kind;
        private final long startedAt = System.currentTimeMillis();
        private volatile State state = State.RUNNING;
        private volatile LlmService.ChatResult result;
        private volatile String error;
        private volatile long finishedAt;
        private volatile Future<?> future;

        Job(String kind) {
            this.kind = kind;
        }

        public String getId() {
            return id;
        }

        public State getState() {
            return state;
        }

        public LlmService.ChatResult getResult() {
            return result;
        }

        public String getError() {
            return error;
        }

        public long getElapsedMs() {
            return (state == State.RUNNING ? System.currentTimeMillis() : finishedAt) - startedAt;
        }
    }

    private final LlmService llmService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "portfolio-analysis");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Job> jobs = new LinkedHashMap<>();

    public AnalysisJobService(LlmService llmService) {
        this.llmService = llmService;
    }

    /** Starts an analysis, or returns the one of the same kind already running. */
    public synchronized Job start(String kind, String prompt) {
        for (Job existing : jobs.values()) {
            if (existing.state == State.RUNNING && existing.kind.equals(kind)) {
                return existing;
            }
        }
        Job job = new Job(kind);
        jobs.put(job.id, job);
        while (jobs.size() > MAX_JOBS_KEPT) {
            jobs.remove(jobs.keySet().iterator().next());
        }
        job.future = executor.submit(() -> {
            try {
                LlmService.ChatResult result = llmService.chat(prompt, null);
                finish(job, State.DONE, result, null);
            } catch (Exception e) {
                finish(job, State.FAILED, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return job;
    }

    /** Only the first terminal state sticks, so a cancelled job is never overwritten by its dying thread. */
    private static void finish(Job job, State state, LlmService.ChatResult result, String error) {
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return;
            }
            job.result = result;
            job.error = error;
            job.finishedAt = System.currentTimeMillis();
            job.state = state;
        }
    }

    /**
     * Cancels a running analysis by interrupting its thread, which aborts the in-flight
     * request to the model server. Returns false if the job is unknown or already finished.
     */
    public boolean cancel(String id) {
        Job job = get(id);
        if (job == null) {
            return false;
        }
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return false;
            }
            job.finishedAt = System.currentTimeMillis();
            job.state = State.CANCELLED;
        }
        Future<?> f = job.future;
        if (f != null) {
            f.cancel(true);
        }
        return true;
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
