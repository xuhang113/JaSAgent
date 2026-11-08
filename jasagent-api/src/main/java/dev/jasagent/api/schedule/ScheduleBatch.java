package dev.jasagent.api.schedule;

import dev.jasagent.api.task.TaskSnapshot;

import java.util.List;

public record ScheduleBatch(int taken, List<TaskSnapshot> assigned) {
}
