package dev.jasagent.persist;

/** 检查点可以落在哪一种数据库上。环境没指定时按 MySQL，显式地址再改走 SQLite 或 PostgreSQL。 */
public enum CheckpointBackend {
    /** 工作区数据目录里的 SQLite 文件，不连外部数据库。 */
    SQLITE,
    /** 由 JDBC 地址指定的 PostgreSQL。 */
    POSTGRES,
    /** 本机或 JDBC 地址指定的 MySQL，未写明后端时的默认选择。 */
    MYSQL
}
