package dev.jasagent.persist;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/** 用 DriverManager 按固定地址和账号建立连接的数据源，不维护连接池。登录超时和日志写出器在这里不生效。 */
final class DriverDataSource implements DataSource {
    private final String url;
    private final String user;
    private final String password;

    /**
     * 记下以后每次取连接要用的地址和账号。
     *
     * @param url JDBC 地址
     * @param user 用户名
     * @param password 口令
     */
    DriverDataSource(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /**
     * 用构造时的账号向驱动要一条新连接。
     *
     * @return 新的数据库连接
     * @throws SQLException 驱动拒绝连接时抛出
     */
    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /**
     * 用调用方临时给的账号向驱动要一条新连接，不用构造时记下的账号。
     *
     * @param username 这次使用的用户名
     * @param password 这次使用的口令
     * @return 新的数据库连接
     * @throws SQLException 驱动拒绝连接时抛出
     */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    /**
     * 这个数据源不记录驱动日志。
     *
     * @return 始终为空
     */
    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    /**
     * 不保存日志写出器，传入的写出器被忽略。
     *
     * @param out 调用方准备的写出器
     */
    @Override
    public void setLogWriter(PrintWriter out) {
    }

    /**
     * 不设置登录超时，传入的秒数被忽略。
     *
     * @param seconds 调用方请求的超时秒数
     */
    @Override
    public void setLoginTimeout(int seconds) {
    }

    /**
     * 登录超时没有被这个数据源接管。
     *
     * @return 始终是 0
     */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    /**
     * 不提供父日志器。
     *
     * @return 不会返回
     * @throws SQLFeatureNotSupportedException 始终抛出
     */
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    /**
     * 请求的类型就是这个数据源本身时返回自己，否则拒绝展开。
     *
     * @param <T> 调用方期望展开成的类型
     * @param iface 调用方期望的类型
     * @return 当前数据源
     * @throws SQLException 类型对不上时抛出
     */
    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("无法展开数据源");
    }

    /**
     * 判断这个数据源是不是请求类型的实例。
     *
     * @param iface 要测试的类型
     * @return 当前对象能当成该类型时为真
     */
    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
