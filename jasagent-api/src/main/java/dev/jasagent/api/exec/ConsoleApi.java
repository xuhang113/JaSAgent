package dev.jasagent.api.exec;

import java.util.List;
import java.util.Map;

/**
 * 控制台读取和修改会话、模型、上下文与通行证决定的入口。返回值是直接交给页面的字段映射。
 */
public interface ConsoleApi {
    /**
     * 列出当前用户在已选工作区里能运行的工作流，并始终包含内置的 answer-with-tool。
     *
     * @param userId 当前用户登录名，必须与令牌里的登录名一致
     * @param token 已登录会话的令牌，用来确认身份并定位工作区
     * @return 工作流行，每行含 id
     */
    List<Map<String, Object>> workflows(String userId, String token);

    /**
     * 列出当前用户目录和工作区合并后的技能名称与说明。
     *
     * @param userId 当前用户登录名，必须与令牌里的登录名一致
     * @param token 已登录会话的令牌，用来装入该用户的技能目录
     * @return 技能行，每行含 name 和 description
     */
    List<Map<String, Object>> skills(String userId, String token);

    /**
     * 列出这个用户已经保存的会话摘要，按创建时间排序。
     *
     * @param userId 会话所属的用户登录名
     * @return 会话摘要；用户还没有会话目录时为空列表
     */
    List<Map<String, Object>> sessions(String userId);

    /**
     * 为用户新建一场会话并写到用户目录。标题为空或属于自动标题时使用「新会话」，同时写入默认模型。
     *
     * @param userId 会话所属的用户登录名
     * @param title 用户提交的标题；空白时改成「新会话」
     * @return 新建会话的摘要，含会话编号、标题和默认模型
     */
    Map<String, Object> createSession(String userId, String title);

    /**
     * 修改一场会话的标题，并把它标成用户手工改过，之后首条消息不再覆盖标题。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号
     * @param title 新标题，空白时拒绝
     * @return 更新后的会话摘要
     */
    Map<String, Object> renameSession(String userId, String sessionId, String title);

    /**
     * 把一场会话切换到目录里存在的模型和供应方，并立即写回会话文件。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号
     * @param model 模型标识
     * @param provider 模型所属供应方，必须与模型标识配成目录中的一项
     * @return 更新后的会话摘要
     */
    Map<String, Object> updateModel(String userId, String sessionId, String model, String provider);

    /**
     * 返回当前可选模型，以及新建会话时使用的默认模型。
     *
     * @param userId 查询模型目录的用户登录名
     * @return 含 defaultModel 和 models 的映射；models 的每行含 id、provider、label
     */
    Map<String, Object> models(String userId);

    /**
     * 删除一场属于该用户的会话文件。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号
     * @return 含 sessionId 和 deleted 的映射，删除成功时 deleted 为 true
     */
    Map<String, Object> deleteSession(String userId, String sessionId);

    /**
     * 读取一场会话的完整内容，补上当前模型、纠正记录和智能体列表。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号
     * @return 会话文件内容，并含 model、modelProvider、corrections、agents
     */
    Map<String, Object> session(String userId, String sessionId);

    /**
     * 把一条用户消息追加到会话，并按会话当前的模型提交工作流。模型保持不变。
     *
     * @param userId 会话所属的用户登录名
     * @param token 已登录会话的令牌，提交执行前用来确认工作区
     * @param sessionId 会话编号
     * @param text 用户消息正文，空白时拒绝
     * @param workflowId 要运行的工作流；空白时使用 answer-with-tool
     * @return 写入会话的用户消息，含 role、text、executionId 和 createdAt
     */
    Map<String, Object> appendMessage(String userId, String token, String sessionId, String text, String workflowId);

    /**
     * 把一条用户消息追加到会话。模型标识非空时先切换会话模型，再按该模型提交工作流。
     * 这是会话的第一条消息且标题未被手工改过时，用消息正文生成标题。
     *
     * @param userId 会话所属的用户登录名
     * @param token 已登录会话的令牌，提交执行前用来确认工作区
     * @param sessionId 会话编号
     * @param text 用户消息正文，空白时拒绝
     * @param workflowId 要运行的工作流；空白时使用 answer-with-tool
     * @param model 这次要改用的模型；空白时沿用会话里已有的模型
     * @param provider 与 model 配套的供应方；model 空白时不使用
     * @return 写入会话的用户消息，含 role、text、executionId 和 createdAt
     */
    Map<String, Object> appendMessage(
            String userId,
            String token,
            String sessionId,
            String text,
            String workflowId,
            String model,
            String provider);

    /**
     * 组装一场会话当前能看到的上下文：规则、技能、纠正、消息、token 预算、压缩进度和已有摘要。
     *
     * @param userId 会话所属的用户登录名
     * @param token 已登录会话的令牌，用来装入规则和技能
     * @param sessionId 会话编号
     * @return 含 rules、skills、corrections、messages、budgetTokens、compressAt、occupancy、summary、compressing 的映射
     */
    Map<String, Object> context(String userId, String token, String sessionId);

    /**
     * 读取一场执行在时间线上已经记下的消息。执行编号为空时，改用该会话里最近一条带执行编号的消息。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号，执行必须挂在这个会话的某条消息上
     * @param executionId 执行编号；空白时从会话消息里倒序寻找最近的执行
     * @return 含 sessionId、executionId 和 messages 的映射
     */
    Map<String, Object> timeline(String userId, String sessionId, String executionId);

    /**
     * 给一场会话追加一条来源为 USER 的纠正记录，供后续上下文引用。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号
     * @param text 纠正正文，空白时拒绝
     * @param executionId 这条纠正针对的执行；没有具体执行时按空串保存
     * @return 写下的纠正记录，含 noteId、sessionId、executionId、createdAt、source 和 text
     */
    Map<String, Object> addCorrection(String userId, String sessionId, String text, String executionId);

    /**
     * 列出一场会话已经保存的纠正记录。
     *
     * @param userId 会话所属的用户登录名
     * @param sessionId 会话编号
     * @return 纠正记录列表；该会话还没有纠正目录时为空列表
     */
    List<Map<String, Object>> corrections(String userId, String sessionId);

    /**
     * 对某次执行上的一张通行证作出允许或拒绝。允许且执行正挂起时继续运行。
     *
     * @param userId 执行所属的用户登录名
     * @param executionId 执行编号
     * @param passId 通行证编号，必须属于这次执行
     * @param decision 用户决定，只接受 ALLOW 或 DENY
     * @return 决定写回后的执行视图
     */
    Map<String, Object> decidePass(String userId, String executionId, String passId, String decision);
}
