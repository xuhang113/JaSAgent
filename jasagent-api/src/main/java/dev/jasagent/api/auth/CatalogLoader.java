package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

/**
 * 规则和技能目录装载器。先读工作区，再读用户目录，同编号规则和同名技能以后读到的用户目录为准。
 */
public interface CatalogLoader {
    /**
     * 从工作区和用户的 jasagent 目录装入规则与技能。
     *
     * @param workspaceDirectory 工作区的 jasagent 目录，读取其中的 rules 与 skills；为 null 时跳过
     * @param userDirectory 用户的 jasagent 目录，同样读取 rules 与 skills，并覆盖工作区里的同名条目
     * @return 完成后给出合并目录的阶段
     */
    CompletionStage<Catalog> load(Path workspaceDirectory, Path userDirectory);
}
