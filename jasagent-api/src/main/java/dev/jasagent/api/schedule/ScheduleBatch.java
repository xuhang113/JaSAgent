package dev.jasagent.api.schedule;

import dev.jasagent.api.task.TaskSnapshot;

import java.util.List;

/**
 * 一次调度从主队列取出并分派的结果。
 * 返回时这些工作项仍是刚分派的排队状态，子代理可以还没跑完。
 *
 * @param taken 这次从主队列头部取走的条数；主队列为空时为 0
 * @param assigned 被分派出去的任务快照，条数与 {@code taken} 一致；没取到时为空列表
 */
public record ScheduleBatch(int taken, List<TaskSnapshot> assigned) {
}
