package gr.gousiosg.javacg.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Long-running tool calls: they return a job id at once, and job_status reports progress and the result. */
final class Jobs {

    enum Status {
        RUNNING, DONE, FAILED, CANCELLED
    }

    /** What a background tool does; it reports progress and should stop when {@link Job#cancelled()}. */
    @FunctionalInterface
    interface Work {
        Object run(Job job) throws Exception;
    }

    static final class Job {
        final String id;
        final String tool;
        final Instant started = Instant.now();
        private final List<String> progress = new ArrayList<>();
        private final CountDownLatch finished = new CountDownLatch(1);
        volatile Status status = Status.RUNNING;
        volatile Object result;
        volatile String error;
        volatile Instant ended;
        volatile boolean cancelRequested;
        Future<?> future;

        Job(String id, String tool) {
            this.id = id;
            this.tool = tool;
        }

        void progress(String line) {
            synchronized (progress) {
                progress.add(line);
                if (progress.size() > 500) {
                    progress.remove(0);
                }
            }
        }

        List<String> progress() {
            synchronized (progress) {
                return List.copyOf(progress);
            }
        }

        boolean cancelled() {
            return cancelRequested || Thread.currentThread().isInterrupted();
        }
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final AtomicInteger next = new AtomicInteger(1);
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "javacg-job");
        t.setDaemon(true);
        return t;
    });

    Job start(String tool, Work work) {
        Job job = new Job("j" + next.getAndIncrement(), tool);
        jobs.put(job.id, job);
        job.future = pool.submit(() -> {
            try {
                Object r = work.run(job);
                if (job.cancelRequested) {
                    job.status = Status.CANCELLED;
                } else {
                    job.result = r;
                    job.status = Status.DONE;
                }
            } catch (InterruptedException e) {
                job.status = Status.CANCELLED;
            } catch (ToolException e) {
                job.error = e.getMessage();
                job.status = job.cancelRequested ? Status.CANCELLED : Status.FAILED;
            } catch (Exception | LinkageError e) {
                job.error = e.toString();
                job.status = job.cancelRequested ? Status.CANCELLED : Status.FAILED;
            } finally {
                job.ended = Instant.now();
                job.finished.countDown();
            }
        });
        return job;
    }

    /** The reply of a tool that started a job. */
    static Map<String, Object> started(Job job, String what) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.id);
        m.put("status", "running");
        m.put("note", what + " runs in the background: call job_status with this job_id (wait_seconds up to 60 "
                + "waits for it), job_cancel to stop it");
        return m;
    }

    List<ToolDef> tools() {
        return List.of(
                new ToolDef("job_status", "Job status", """
                        Status, progress and, once finished, the result of a background job started by a tool such \
                        as bisect_run, or watch_method / jfr_analyze with background=true. wait_seconds waits for \
                        the job to finish (up to 60 s) before answering.""", true,
                        Schema.object().string("job_id", "Job id returned by the tool", true)
                                .integer("wait_seconds", "Wait up to this long for the job to finish (max 60)", 0)
                                .integer("progress_lines", "Last progress lines returned", 20).build(),
                        args -> {
                            Job job = job(args);
                            int wait = Math.max(0, Math.min(60, args.integer("wait_seconds", 0)));
                            if (wait > 0) {
                                job.finished.await(wait, TimeUnit.SECONDS);
                            }
                            return view(job, args.integer("progress_lines", 20));
                        }),
                new ToolDef("job_list", "List jobs", "Background jobs of this server, newest first.", true,
                        Schema.object().build(),
                        args -> Map.of("jobs", jobs.values().stream()
                                .sorted((a, b) -> b.started.compareTo(a.started)).map(j -> {
                                    Map<String, Object> m = new LinkedHashMap<>();
                                    m.put("job_id", j.id);
                                    m.put("tool", j.tool);
                                    m.put("status", Views.lower(j.status));
                                    m.put("seconds", seconds(j));
                                    return m;
                                }).toList())),
                new ToolDef("job_cancel", "Cancel a job", "Stops a background job; it cleans up (e.g. bisect "
                        + "removes its worktree) before reporting cancelled.", false,
                        Schema.object().string("job_id", "Job id", true).build(),
                        args -> {
                            Job job = job(args);
                            if (job.status == Status.RUNNING) {
                                job.cancelRequested = true;
                                job.future.cancel(true);
                                job.finished.await(30, TimeUnit.SECONDS);
                            }
                            return view(job, 5);
                        }));
    }

    private Job job(ToolArgs args) {
        String id = args.require("job_id");
        Job job = jobs.get(id);
        if (job == null) {
            throw new ToolException("Unknown job_id " + id + (jobs.isEmpty() ? "" : "; jobs: " + jobs.keySet()));
        }
        return job;
    }

    private static Map<String, Object> view(Job job, int lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job_id", job.id);
        m.put("tool", job.tool);
        m.put("status", Views.lower(job.status));
        m.put("seconds", seconds(job));
        List<String> p = job.progress();
        if (!p.isEmpty()) {
            m.put("progress", p.subList(Math.max(0, p.size() - lines), p.size()));
        }
        if (job.error != null) {
            m.put("error", job.error);
        }
        if (job.result != null) {
            m.put("result", job.result);
        }
        return m;
    }

    private static double seconds(Job j) {
        Instant end = j.ended == null ? Instant.now() : j.ended;
        return Math.round(Duration.between(j.started, end).toMillis() / 100.0) / 10.0;
    }
}
