package dev.jasagent.core;

import dev.jasagent.api.concurrent.VirtualIo;
import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;
import dev.jasagent.api.schedule.AgentScheduler;
import dev.jasagent.api.schedule.ScheduleBatch;
import dev.jasagent.api.schedule.SubAgentRunner;
import dev.jasagent.api.task.AgentRole;
import dev.jasagent.api.task.TaskSnapshot;
import dev.jasagent.api.task.WorkItem;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

public final class QueueAgentScheduler implements AgentScheduler {
    public static final String MAIN_ID = "main";
    private static final double EPSILON = 1e-12;

    private final Object lock = new Object();
    private final SchedulerSettings settings;
    private final SubAgentRunner runner;
    private final ArrayDeque<Task> mainQueue = new ArrayDeque<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<SubAgent> subAgents = new ArrayList<>();
    private final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>();
    private int subSequence;

    public QueueAgentScheduler(Map<String, String> configuration, SubAgentRunner runner) {
        if (runner == null) {
            throw new AgentException(ErrorCode.INTERNAL, "SubAgentRunner");
        }
        this.settings = SchedulerSettings.from(configuration);
        this.runner = runner;
    }

    public SchedulerSettings settings() {
        return settings;
    }

    @Override
    public CompletionStage<Void> enqueue(WorkItem item) {
        return VirtualIo.supply(() -> {
            synchronized (lock) {
                accept(item);
            }
            return null;
        });
    }

    @Override
    public CompletionStage<ScheduleBatch> scheduleOnce() {
        return VirtualIo.supply(() -> {
            ScheduleBatch batch;
            List<SubAgent> start;
            synchronized (lock) {
                Assignment assignment = assignLocked();
                batch = assignment.batch;
                start = assignment.start;
            }
            for (SubAgent agent : start) {
                agent.kick();
            }
            return batch;
        });
    }

    public List<String> agentIds() {
        synchronized (lock) {
            List<String> ids = new ArrayList<>();
            ids.add(MAIN_ID);
            for (SubAgent agent : subAgents) {
                ids.add(agent.id);
            }
            return List.copyOf(ids);
        }
    }

    public List<TaskSnapshot> queue(String agentId) {
        synchronized (lock) {
            if (MAIN_ID.equals(agentId)) {
                return snapshots(mainQueue);
            }
            for (SubAgent agent : subAgents) {
                if (agent.id.equals(agentId)) {
                    return snapshots(agent.queue);
                }
            }
            return List.of();
        }
    }

    public CompletionStage<Void> onIdle() {
        synchronized (lock) {
            if (isIdleLocked()) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            idleWaiters.add(waiter);
            return waiter;
        }
    }

    private void accept(WorkItem item) {
        if (item == null || blank(item.taskId()) || blank(item.executionId())) {
            throw new AgentException(ErrorCode.INTERNAL, "workItem");
        }
        if (tasks.containsKey(item.taskId())) {
            throw new AgentException(ErrorCode.INTERNAL, "taskId");
        }
        String sessionId = blank(item.sessionId()) ? item.executionId() : item.sessionId();
        WorkItem stored = new WorkItem(
                item.taskId(),
                item.executionId(),
                sessionId,
                item.correlationKey(),
                item.targetText(),
                item.workflowId(),
                item.input());
        Task task = new Task(stored, MAIN_ID, AgentRole.MAIN, "QUEUED");
        tasks.put(stored.taskId(), task);
        mainQueue.add(task);
    }

    private Assignment assignLocked() {
        int take = Math.min(settings.batchSize(), mainQueue.size());
        List<Task> taken = new ArrayList<>(take);
        for (int index = 0; index < take; index++) {
            taken.add(mainQueue.removeFirst());
        }
        List<SubAgent> start = new ArrayList<>();
        for (List<Task> cluster : cluster(taken)) {
            place(cluster, start);
        }
        List<TaskSnapshot> assigned = new ArrayList<>(taken.size());
        for (Task task : taken) {
            assigned.add(task.snapshot());
        }
        return new Assignment(new ScheduleBatch(take, List.copyOf(assigned)), start);
    }

    private List<List<Task>> cluster(List<Task> taken) {
        int count = taken.size();
        int[] parent = new int[count];
        for (int index = 0; index < count; index++) {
            parent[index] = index;
        }
        for (int left = 0; left < count; left++) {
            for (int right = left + 1; right < count; right++) {
                if (meets(WorkCorrelation.score(taken.get(left).item, taken.get(right).item))) {
                    union(parent, left, right);
                }
            }
        }
        Map<Integer, List<Task>> groups = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            groups.computeIfAbsent(find(parent, index), key -> new ArrayList<>()).add(taken.get(index));
        }
        return new ArrayList<>(groups.values());
    }

    private void place(List<Task> cluster, List<SubAgent> start) {
        SubAgent target = null;
        for (Task task : cluster) {
            if (target == null) {
                target = select(cluster);
            } else if (unfinished(target) >= settings.queueCap()) {
                target = createSub();
            }
            task.agentId = target.id;
            task.role = AgentRole.SUB;
            task.status = "QUEUED";
            target.queue.add(task);
            if (!start.contains(target)) {
                start.add(target);
            }
        }
    }

    private SubAgent select(List<Task> cluster) {
        SubAgent best = null;
        double bestScore = -1.0;
        for (SubAgent agent : subAgents) {
            if (unfinished(agent) == 0) {
                continue;
            }
            double score = correlation(cluster, agent);
            if (!meets(score) || best != null && score <= bestScore + EPSILON) {
                continue;
            }
            best = agent;
            bestScore = score;
        }
        if (best == null || unfinished(best) >= settings.queueCap()) {
            return createSub();
        }
        return best;
    }

    private double correlation(List<Task> cluster, SubAgent agent) {
        double best = -1.0;
        for (Task incoming : cluster) {
            for (Task existing : agent.queue) {
                if (terminal(existing.status)) {
                    continue;
                }
                best = Math.max(best, WorkCorrelation.score(incoming.item, existing.item));
            }
        }
        return best;
    }

    private SubAgent createSub() {
        subSequence++;
        SubAgent agent = new SubAgent("sub-" + subSequence);
        subAgents.add(agent);
        return agent;
    }

    private int unfinished(SubAgent agent) {
        int count = 0;
        for (Task task : agent.queue) {
            if (!terminal(task.status)) {
                count++;
            }
        }
        return count;
    }

    private boolean meets(double score) {
        return score + EPSILON >= settings.mergeThreshold();
    }

    private boolean isIdleLocked() {
        for (SubAgent agent : subAgents) {
            if (agent.pumping.get() || unfinished(agent) > 0) {
                return false;
            }
        }
        return true;
    }

    private void notifyIdleLocked() {
        if (!isIdleLocked()) {
            return;
        }
        for (CompletableFuture<Void> waiter : idleWaiters) {
            waiter.complete(null);
        }
        idleWaiters.clear();
    }

    private static List<TaskSnapshot> snapshots(Iterable<Task> tasks) {
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (Task task : tasks) {
            snapshots.add(task.snapshot());
        }
        return List.copyOf(snapshots);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status)
                || "SUSPENDED".equals(status);
    }

    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot == rightRoot) {
            return;
        }
        if (leftRoot < rightRoot) {
            parent[rightRoot] = leftRoot;
        } else {
            parent[leftRoot] = rightRoot;
        }
    }

    private static int find(int[] parent, int index) {
        int root = index;
        while (parent[root] != root) {
            root = parent[root];
        }
        int cursor = index;
        while (cursor != root) {
            int next = parent[cursor];
            parent[cursor] = root;
            cursor = next;
        }
        return root;
    }

    private static final class Task {
        private final WorkItem item;
        private String agentId;
        private AgentRole role;
        private String status;

        private Task(WorkItem item, String agentId, AgentRole role, String status) {
            this.item = item;
            this.agentId = agentId;
            this.role = role;
            this.status = status;
        }

        private TaskSnapshot snapshot() {
            return new TaskSnapshot(item.taskId(), agentId, role, status, item);
        }
    }

    private static final class Assignment {
        private final ScheduleBatch batch;
        private final List<SubAgent> start;

        private Assignment(ScheduleBatch batch, List<SubAgent> start) {
            this.batch = batch;
            this.start = start;
        }
    }

    private final class SubAgent {
        private final String id;
        private final ArrayDeque<Task> queue = new ArrayDeque<>();
        private final AtomicBoolean pumping = new AtomicBoolean();

        private SubAgent(String id) {
            this.id = id;
        }

        private void kick() {
            if (!pumping.compareAndSet(false, true)) {
                return;
            }
            VirtualIo.supply(() -> {
                try {
                    drain();
                    return null;
                } finally {
                    boolean again;
                    synchronized (lock) {
                        pumping.set(false);
                        again = hasQueued();
                        if (!again) {
                            notifyIdleLocked();
                        }
                    }
                    if (again) {
                        kick();
                    }
                }
            });
        }

        private void drain() {
            while (true) {
                Task task;
                synchronized (lock) {
                    task = nextQueued();
                    if (task == null) {
                        return;
                    }
                    task.status = "RUNNING";
                }
                CompletionStage<Void> stage;
                try {
                    stage = runner.run(task.item);
                    if (stage == null) {
                        stage = CompletableFuture.failedFuture(new AgentException(ErrorCode.INTERNAL, "runner"));
                    }
                } catch (RuntimeException ex) {
                    stage = CompletableFuture.failedFuture(ex);
                }
                try {
                    stage.toCompletableFuture().join();
                    synchronized (lock) {
                        task.status = "SUCCEEDED";
                    }
                } catch (RuntimeException ex) {
                    synchronized (lock) {
                        task.status = "FAILED";
                    }
                }
            }
        }

        private Task nextQueued() {
            for (Task task : queue) {
                if ("QUEUED".equals(task.status)) {
                    return task;
                }
            }
            return null;
        }

        private boolean hasQueued() {
            return nextQueued() != null;
        }
    }
}
