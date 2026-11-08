package dev.jasagent.core;

import dev.jasagent.api.workflow.WorkflowResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentScheduler {
    private final SchedulerConfig config;
    private final WorkflowEngine engine;
    private final Deque<WorkItem> mainQueue = new ArrayDeque<>();
    private final List<SubAgent> subagents = new ArrayList<>();
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private int sequence;

    public AgentScheduler(SchedulerConfig config, WorkflowEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    public void enqueue(WorkItem item) {
        String sessionId = item.sessionId() == null || item.sessionId().isBlank() ? item.executionId() : item.sessionId();
        mainQueue.add(new WorkItem(item.taskId(), item.executionId(), sessionId, item.correlationKey(), item.targetText(), item.workflowJson(), item.input()));
    }

    public int mainQueueSize() {
        return mainQueue.size();
    }

    public int subagentCount() {
        return subagents.size();
    }

    public void scheduleOnce() {
        List<WorkItem> batch = new ArrayList<>();
        while (batch.size() < config.batchSize() && !mainQueue.isEmpty()) {
            batch.add(mainQueue.removeFirst());
        }
        for (WorkItem item : cluster(batch)) {
            assign(item);
        }
    }

    public static double similarity(WorkItem left, WorkItem right) {
        if (left.sessionId() != null && left.sessionId().equals(right.sessionId())) {
            return 1.0;
        }
        if (left.correlationKey() != null && !left.correlationKey().isBlank() && left.correlationKey().equals(right.correlationKey())) {
            return 0.9;
        }
        return jaccard(left.targetText(), right.targetText());
    }

    private List<WorkItem> cluster(List<WorkItem> batch) {
        List<List<WorkItem>> groups = new ArrayList<>();
        for (WorkItem item : batch) {
            List<WorkItem> match = null;
            for (List<WorkItem> group : groups) {
                if (similarity(group.get(0), item) >= config.mergeThreshold()) {
                    match = group;
                    break;
                }
            }
            if (match == null) {
                List<WorkItem> created = new ArrayList<>();
                created.add(item);
                groups.add(created);
            } else {
                match.add(item);
            }
        }
        List<WorkItem> merged = new ArrayList<>();
        for (List<WorkItem> group : groups) {
            if (group.size() == 1) {
                merged.add(group.get(0));
                continue;
            }
            String text = String.join("\n", group.stream().map(WorkItem::targetText).toList());
            WorkItem first = group.get(0);
            merged.add(new WorkItem(first.taskId(), first.executionId(), first.sessionId(), first.correlationKey(), text, first.workflowJson(), text));
        }
        return merged;
    }

    private void assign(WorkItem item) {
        SubAgent target = null;
        for (SubAgent agent : subagents) {
            if (agent.unfinished() >= config.queueCap()) {
                continue;
            }
            if (agent.related(item)) {
                target = agent;
                break;
            }
        }
        if (target == null) {
            target = new SubAgent("sub-" + (++sequence));
            subagents.add(target);
        }
        target.offer(item);
    }

    private static double jaccard(String left, String right) {
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        if (leftTokens.isEmpty() && rightTokens.isEmpty()) {
            return 0;
        }
        long intersection = leftTokens.stream().filter(rightTokens::contains).count();
        Set<String> union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return intersection / (double) union.size();
    }

    private static Set<String> tokens(String text) {
        Set<String> tokens = new HashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        for (String token : text.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private final class SubAgent {
        private final String id;
        private final Deque<WorkItem> queue = new ArrayDeque<>();
        private WorkItem running;
        private boolean pumping;

        private SubAgent(String id) {
            this.id = id;
        }

        private int unfinished() {
            return queue.size() + (running == null ? 0 : 1);
        }

        private boolean related(WorkItem item) {
            if (running != null && similarity(running, item) >= config.mergeThreshold()) {
                return true;
            }
            for (WorkItem queued : queue) {
                if (similarity(queued, item) >= config.mergeThreshold()) {
                    return true;
                }
            }
            return false;
        }

        private void offer(WorkItem item) {
            CompletionStage<WorkflowResult> started = null;
            synchronized (this) {
                queue.add(item);
                if (!pumping) {
                    pumping = true;
                    running = queue.poll();
                    started = engine.execute(new AgentTask(id, "SUB", running));
                }
            }
            if (started != null) {
                continueAfter(started);
            }
        }

        private void continueAfter(CompletionStage<WorkflowResult> current) {
            current.whenComplete((result, error) -> workers.execute(() -> {
                WorkItem next;
                synchronized (this) {
                    running = null;
                    next = queue.poll();
                    if (next == null) {
                        pumping = false;
                        return;
                    }
                    running = next;
                }
                continueAfter(engine.execute(new AgentTask(id, "SUB", next)));
            }));
        }
    }
}
