package dev.jasagent.api.rag;

/**
 * 检索语料所在的目录范围。
 * 用户范围读用户目录下的文档，工作区范围读工作区目录下的文档。
 */
public enum SourceScope {
    /**
     * 用户目录。文档和索引都在该用户自己的 {@code rag} 目录里。
     */
    USER,
    /**
     * 工作区目录。文档和索引都在调用方给出的工作区里。
     */
    WORKSPACE
}
