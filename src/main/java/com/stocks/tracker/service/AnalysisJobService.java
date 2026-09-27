package com.stocks.tracker.service;

import com.stocks.tracker.model.SavedAnalysis;
import com.stocks.tracker.repository.SavedAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
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

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobService.class);
    private static final int MAX_JOBS_KEPT = 20;
    /** Kind of job whose results are saved for later reference. */
    public static final String KIND_ANALYSIS = "analysis";

    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    public static final class Job {
        private final String id = UUID.randomUUID().toString();
        private final String kind;
        private final long startedAt = System.currentTimeMillis();
        private volatile State state = State.RUNNING;
        private volatile String phase = "Starting";
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

        /** What a running job is doing right now, for progress display. */
        public String getPhase() {
            return phase;
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

    private final SavedAnalysisRepository savedAnalyses;

    private final SpeculationService speculation;

    public AnalysisJobService(LlmService llmService, SavedAnalysisRepository savedAnalyses,
                              SpeculationService speculation) {
        this.llmService = llmService;
        this.savedAnalyses = savedAnalyses;
        this.speculation = speculation;
    }

    /** Builds a prompt in the background (it may fetch data); reports progress through {@code phase}. */
    public interface PromptBuilder {
        String build(java.util.function.Consumer<String> phase) throws Exception;
    }

    /** A background model call that reports progress through {@code phase}. */
    public interface ChatTask {
        LlmService.ChatResult run(java.util.function.Consumer<String> phase) throws Exception;
    }

    /** Starts a job for a ready-made prompt, or returns the one of the same kind already running. */
    public synchronized Job start(String kind, String prompt) {
        return start(kind, phase -> prompt);
    }

    /**
     * Starts a job whose prompt is built in the background, or returns the one of the same kind already
     * running. The built prompt is what gets sent to the model and saved with the analysis.
     */
    public synchronized Job start(String kind, PromptBuilder builder) {
        for (Job existing : jobs.values()) {
            if (existing.state == State.RUNNING && existing.kind.equals(kind)) {
                return existing;
            }
        }
        java.util.concurrent.atomic.AtomicReference<String> built = new java.util.concurrent.atomic.AtomicReference<>();
        return submit(kind, built, phase -> {
            String prompt = builder.build(phase);
            built.set(prompt);
            phase.accept("Waiting for the model");
            return llmService.chat(prompt, null);
        });
    }

    /**
     * Runs an arbitrary model call in the background (used for chat). Unlike {@link #start}, a second
     * request while one is running is refused (returns null) rather than joined, since each call is
     * a different question.
     */
    public synchronized Job startExclusive(String kind, ChatTask task) {
        for (Job existing : jobs.values()) {
            if (existing.state == State.RUNNING && existing.kind.equals(kind)) {
                return null;
            }
        }
        return submit(kind, new java.util.concurrent.atomic.AtomicReference<>(), task);
    }

    private Job submit(String kind, java.util.concurrent.atomic.AtomicReference<String> prompt, ChatTask task) {
        Job job = new Job(kind);
        jobs.put(job.id, job);
        while (jobs.size() > MAX_JOBS_KEPT) {
            jobs.remove(jobs.keySet().iterator().next());
        }
        job.future = executor.submit(() -> {
            try {
                LlmService.ChatResult result = task.run(p -> job.phase = p);
                if (KIND_ANALYSIS.equals(job.kind)) {
                    result = withAiPicks(result);
                }
                if (finish(job, State.DONE, result, null) && KIND_ANALYSIS.equals(job.kind)) {
                    save(prompt.get(), result);
                }
            } catch (Exception e) {
                finish(job, State.FAILED, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return job;
    }

    /** Only the first terminal state sticks, so a cancelled job is never overwritten by its dying thread. */
    private static boolean finish(Job job, State state, LlmService.ChatResult result, String error) {
        synchronized (job) {
            if (job.state != State.RUNNING) {
                return false;
            }
            job.result = result;
            job.error = error;
            job.finishedAt = System.currentTimeMillis();
            job.state = state;
            return true;
        }
    }

    /** Adds any stocks the model recommended to the Speculation list; the reply is kept if that fails. */
    private LlmService.ChatResult withAiPicks(LlmService.ChatResult result) {
        try {
            String text = speculation.addAiPicks(result.response()).response();
            return new LlmService.ChatResult(result.model(), text, result.durationMs());
        } catch (Exception e) {
            log.warn("Could not process AI speculation picks: {}", e.getMessage());
            return result;
        }
    }

    /** A failure to save must not turn a finished analysis into a failed one. */
    private void save(String prompt, LlmService.ChatResult result) {
        try {
            savedAnalyses.save(new SavedAnalysis(LocalDateTime.now(), result.model(), result.durationMs(), prompt, result.response()));
        } catch (Exception e) {
            log.warn("Could not save analysis: {}", e.getMessage());
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

    /** The most recently started job of the given kind, or null if there is none (e.g. after a restart). */
    public synchronized Job latest(String kind) {
        Job found = null;
        for (Job j : jobs.values()) {
            if (j.kind.equals(kind)) {
                found = j;
            }
        }
        return found;
    }

    public synchronized Job get(String id) {
        return jobs.get(id);
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
