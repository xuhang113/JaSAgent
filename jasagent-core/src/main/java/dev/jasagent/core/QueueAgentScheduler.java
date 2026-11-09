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

/**
 * 把待办先放在主队列，再按相关度分给子代理；每个子代理串行执行，不同子代理可以并行。
 */
public final class QueueAgentScheduler implements AgentScheduler {
    /**
     * 主队列的代理标识。主代理只接收和分派，不自己执行待办。
     */
    public static final String MAIN_ID = "main";
    /**
     * 比较相关度时允许的浮点误差，避免刚好等于门槛时被当成低于门槛。
     */
    private static final double EPSILON = 1e-12;

    private final Object lock = new Object();
    private final SchedulerSettings settings;
    private final SubAgentRunner runner;
    private final ArrayDeque<Task> mainQueue = new ArrayDeque<>();
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<SubAgent> subAgents = new ArrayList<>();
    private final List<CompletableFuture<Void>> idleWaiters = new ArrayList<>();
    private int subSequence;

    /**
     * 按配置建立调度器，并绑定真正执行子代理待办的运行器。运行器为空时失败。
     *
     * @param configuration 批大小、合并门槛和队列上限；空则用缺省
     * @param runner 子代理串行调用的执行器
     */
    public QueueAgentScheduler(Map<String, String> configuration, SubAgentRunner runner) {
        if (runner == null) {
            throw new AgentException(ErrorCode.INTERNAL, "SubAgentRunner");
        }
        this.settings = SchedulerSettings.from(configuration);
        this.runner = runner;
    }

    /**
     * 返回这次调度实际采用的批大小、合并门槛和队列上限。
     *
     * @return 构造时解析好的参数
     */
    public SchedulerSettings settings() {
        return settings;
    }

    /**
     * 把待办放进主队列并标成排队，不在这里执行。重复的任务标识或空白标识会被拒绝。
     *
     * @param item 要入队的待办
     * @return 入队完成后的阶段
     */
    @Override
    public CompletionStage<Void> enqueue(WorkItem item) {
        return VirtualIo.supply(() -> {
            synchronized (lock) {
                accept(item);
            }
            return null;
        });
    }

    /**
     * 从主队列最多取出一批，按相关度分组后放进子代理，并启动这些子代理。返回时任务可能仍在跑。
     *
     * @return 本轮取出的数量和分派快照
     */
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

    /**
     * 返回主代理和已经创建的子代理标识，主代理在最前。
     *
     * @return 当前代理标识
     */
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

    /**
     * 返回某个代理队列里的任务快照。不认识的标识得到空列表。
     *
     * @param agentId 主代理或子代理标识
     * @return 该队列当前的任务快照
     */
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

    /**
     * 等待所有子代理都没有在跑、也没有未结束任务。已经空闲时立即完成。
     *
     * @return 全部子代理空闲后完成的阶段
     */
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

    /**
     * 校验标识后把待办放进主队列。空白会话改记成执行标识。副作用是登记任务并追加到主队列。
     *
     * @param item 调用方提交的待办
     */
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

    /**
     * 在已持有锁时取出一批、按相关度聚类并放进子代理。副作用是从主队列移除这些任务并可能新建子代理。
     *
     * @return 本轮批次，以及需要启动抽水的子代理
     */
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

    /**
     * 用单链合并把达到门槛的待办收成一组，组内保持取出时的顺序。
     *
     * @param taken 本轮从主队列取出的待办
     * @return 按组合并后的任务组
     */
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

    /**
     * 把一组任务放进相关且未满的子代理；放不下时为剩余任务另开子代理。副作用是改写任务所属代理并加入子队列。
     *
     * @param cluster 要放在一起的任务
     * @param start 本轮需要启动的子代理，新用到的会追加进去
     */
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

    /**
     * 在未结束的子代理里选与这组相关度最高且未达上限的一个；没有合适的就新建。
     *
     * @param cluster 即将放入的任务组
     * @return 接收这组任务的子代理
     */
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

    /**
     * 取这组任务与该子代理未结束任务之间的最高相关度。
     *
     * @param cluster 即将放入的任务
     * @param agent 已有任务的子代理
     * @return 最高相关度；没有可比较的未结束任务时为负数
     */
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

    /**
     * 按递增序号新建一个子代理并登记。副作用是增加子代理列表。
     *
     * @return 新建的子代理
     */
    private SubAgent createSub() {
        subSequence++;
        SubAgent agent = new SubAgent("sub-" + subSequence);
        subAgents.add(agent);
        return agent;
    }

    /**
     * 统计子代理队列里尚未进入终态的任务数。
     *
     * @param agent 要统计的子代理
     * @return 未结束任务数
     */
    private int unfinished(SubAgent agent) {
        int count = 0;
        for (Task task : agent.queue) {
            if (!terminal(task.status)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断相关度是否达到合并门槛，比较时留出浮点误差。
     *
     * @param score 两条待办或一组与已有任务的相关度
     * @return 达到门槛时为真
     */
    private boolean meets(double score) {
        return score + EPSILON >= settings.mergeThreshold();
    }

    /**
     * 判断是否没有任何子代理正在抽水或仍有未结束任务。调用时须已持有调度锁。
     *
     * @return 全部空闲时为真
     */
    private boolean isIdleLocked() {
        for (SubAgent agent : subAgents) {
            if (agent.pumping.get() || unfinished(agent) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 若当前已经空闲，完成并清掉正在等待空闲的阶段。副作用是唤醒等待方。
     */
    private void notifyIdleLocked() {
        if (!isIdleLocked()) {
            return;
        }
        for (CompletableFuture<Void> waiter : idleWaiters) {
            waiter.complete(null);
        }
        idleWaiters.clear();
    }

    /**
     * 把队列中的任务抄成不可变快照列表。
     *
     * @param tasks 某个代理的任务
     * @return 当前状态的快照
     */
    private static List<TaskSnapshot> snapshots(Iterable<Task> tasks) {
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (Task task : tasks) {
            snapshots.add(task.snapshot());
        }
        return List.copyOf(snapshots);
    }

    /**
     * 判断标识是否没有可用内容。
     *
     * @param value 待检查的文本
     * @return 空引用或空白时为真
     */
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 判断任务状态是否不再继续执行。
     *
     * @param status 任务状态
     * @return 成功、失败、取消或挂起时为真
     */
    private static boolean terminal(String status) {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "CANCELLED".equals(status)
                || "SUSPENDED".equals(status);
    }

    /**
     * 按并查集合并两个下标。副作用是把排名靠后的根指到靠前的根；已经同组时不变。
     *
     * @param parent 每个下标的父下标
     * @param left 左侧任务下标
     * @param right 右侧任务下标
     */
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

    /**
     * 查找下标所在组的根，并沿途把路径直接指到根上。副作用是压缩父链接。
     *
     * @param parent 每个下标的父下标
     * @param index 要查找的任务下标
     * @return 该组的根下标
     */
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

    /**
     * 调度器内部的一条待办，所属代理、角色和状态会在分派和执行时改变。
     */
    private static final class Task {
        private final WorkItem item;
        private String agentId;
        private AgentRole role;
        private String status;

        /**
         * 记下待办及其最初所在的代理、角色和状态。
         *
         * @param item 入队时保存的待办
         * @param agentId 最初是主代理，分派后改为子代理
         * @param role 最初是主角色，分派后改为子角色
         * @param status 入队时为排队
         */
        private Task(WorkItem item, String agentId, AgentRole role, String status) {
            this.item = item;
            this.agentId = agentId;
            this.role = role;
            this.status = status;
        }

        /**
         * 抄出这条任务当前的标识、代理、角色、状态和待办内容。
         *
         * @return 给队列查询和批次结果用的快照
         */
        private TaskSnapshot snapshot() {
            return new TaskSnapshot(item.taskId(), agentId, role, status, item);
        }
    }

    /**
     * 一轮分派的对外批次，以及这轮要启动抽水的子代理。
     */
    private static final class Assignment {
        private final ScheduleBatch batch;
        private final List<SubAgent> start;

        /**
         * 保存本轮已经分派的批次，以及随后要启动抽水的子代理。
         *
         * @param batch 本轮取出数量和任务快照
         * @param start 需要开始串行执行的子代理
         */
        private Assignment(ScheduleBatch batch, List<SubAgent> start) {
            this.batch = batch;
            this.start = start;
        }
    }

    /**
     * 一个子代理的串行队列。同一队列里的任务一个接一个跑，失败后仍继续下一条。
     */
    private final class SubAgent {
        private final String id;
        private final ArrayDeque<Task> queue = new ArrayDeque<>();
        private final AtomicBoolean pumping = new AtomicBoolean();

        /**
         * 创建一个尚未开始抽水的子代理。
         *
         * @param id 形如 sub-序号 的代理标识
         */
        private SubAgent(String id) {
            this.id = id;
        }

        /**
         * 若当前没有在抽水，就在虚拟线程上排空队列；排完后若又有排队任务则再次启动，否则在空闲时唤醒等待方。副作用是改变抽水标记并执行任务。
         */
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

        /**
         * 按排队顺序执行任务，成功标成成功，运行器失败标成失败，然后继续下一条。副作用是改写任务状态并调用运行器。
         */
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

        /**
         * 找到队列里第一条仍在排队的任务。
         *
         * @return 下一条要执行的任务；没有时为空
         */
        private Task nextQueued() {
            for (Task task : queue) {
                if ("QUEUED".equals(task.status)) {
                    return task;
                }
            }
            return null;
        }

        /**
         * 判断队列里是否还有排队任务，用来决定抽水结束后要不要再启动。
         *
         * @return 仍有排队任务时为真
         */
        private boolean hasQueued() {
            return nextQueued() != null;
        }
    }
}
