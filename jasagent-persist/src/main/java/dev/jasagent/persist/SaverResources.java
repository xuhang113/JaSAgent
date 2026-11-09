package dev.jasagent.persist;

import dev.jasagent.api.exception.AgentException;
import dev.jasagent.api.exception.ErrorCode;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 langgraph4j 检查点存储器自带的建表脚本抽到临时目录，并插到类加载器搜索路径最前面。
 * 这样 Saver 打开时读到的是本进程准备的脚本，而不是依赖包里可能对不上的版本。
 */
final class SaverResources {
    /** 串行化对类加载器 SQL 覆盖层的修改，避免两个存储器同时改同一条搜索路径。 */
    static final Object LOCK = new Object();

    /** 要从 Saver 依赖包里抄到临时目录的建表和命令脚本，覆盖 v1 与 v2、以及大小写两种迁移路径。 */
    private static final List<String> FILES = List.of(
            "db/migration/v2.0__init.sql",
            "db/migration/v2.1__init.sql",
            "db/migration/v1.1__init.sql",
            "db/migration/v1.0__init.sql",
            "db/migration/V1.0__init.sql",
            "db/v2.0__commands.sql",
            "db/v2.1__commands.sql",
            "db/v1.1__commands.sql",
            "db/v1.0__commands.sql");

    private static Path root;
    /** 每个类加载器当前挂上的覆盖层。换目录时先把它从搜索路径里卸掉，再插新的。 */
    private static final Map<ClassLoader, Object> overlays = new IdentityHashMap<>();

    /** 不创建实例。脚本覆盖只通过静态方法改进程里的类加载器。 */
    private SaverResources() {
    }

    /**
     * 按 Saver 类找到它所属的依赖包，把包里的 SQL 写进临时目录，并插到系统类加载器和该 Saver 的类加载器最前面。一个文件都没抄到时抛出持久化异常。
     *
     * @param saver 即将打开的检查点存储器类型，用来区分 MySQL、SQLite 和 PostgreSQL 包
     */
    static void use(Class<?> saver) {
        try {
            Path directory = directory();
            clear(directory);
            String token = token(saver);
            ClassLoader origin = saver.getClassLoader();
            int copied = 0;
            for (String name : FILES) {
                try (InputStream input = open(origin, token, name)) {
                    if (input == null) {
                        continue;
                    }
                    Path target = directory.resolve(name);
                    Files.createDirectories(target.getParent());
                    Files.write(target, input.readAllBytes());
                    copied++;
                }
            }
            if (copied == 0) {
                throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开");
            }
            install(origin, directory.toUri().toURL());
        } catch (AgentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new AgentException(ErrorCode.PERSIST, "检查点存储器无法打开", null, ex);
        }
    }

    /**
     * 返回存放覆盖 SQL 的临时目录。第一次调用时创建，之后一直复用，避免每次打开都换路径。
     *
     * @return 临时目录
     * @throws Exception 创建临时目录失败时抛出
     */
    private static Path directory() throws Exception {
        if (root == null) {
            root = Files.createTempDirectory("jasagent-saver-sql");
        }
        return root;
    }

    /**
     * 删掉临时目录里已有的文件和子目录，目录本身留下。按路径深度从深到浅删，避免目录非空删不掉。
     *
     * @param directory 要清空的临时目录
     * @throws Exception 遍历或删除失败时抛出
     */
    private static void clear(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            List<Path> paths = walk.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList();
            for (Path path : paths) {
                if (!path.equals(directory)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * 先在 Saver 自己的类加载器上找脚本，没有再找系统类加载器。只打开地址里带依赖包标记的那一份，避免抄到别的 Saver 的同名文件。
     *
     * @param origin Saver 的类加载器，引导类加载器时为空
     * @param token 依赖包标记，例如 langgraph4j-mysql-saver
     * @param name 类路径上的脚本相对名
     * @return 命中资源的输入流；两处都没有时为空
     * @throws Exception 枚举或打开资源失败时抛出
     */
    private static InputStream open(ClassLoader origin, String token, String name) throws Exception {
        InputStream found = open(origin == null ? null : origin.getResources(name), token);
        if (found != null) {
            return found;
        }
        return open(ClassLoader.getSystemClassLoader().getResources(name), token);
    }

    /**
     * 在一组同名资源里挑出地址包含依赖包标记的那一个并打开。
     *
     * @param urls 类加载器返回的同名资源，可以为空
     * @param token 依赖包标记
     * @return 命中资源的输入流；没有命中时为空
     * @throws Exception 打开流失败时抛出
     */
    private static InputStream open(Enumeration<URL> urls, String token) throws Exception {
        if (urls == null) {
            return null;
        }
        while (urls.hasMoreElements()) {
            URL url = urls.nextElement();
            if (url.toString().contains(token)) {
                return url.openStream();
            }
        }
        return null;
    }

    /**
     * 根据代码位置判断该抄哪个 langgraph4j saver 包。位置对不上时再看类名里有没有 MySQL 或 SQLite，其余按 PostgreSQL。
     *
     * @param saver 检查点存储器类型
     * @return 依赖包标记
     */
    private static String token(Class<?> saver) {
        String location = String.valueOf(saver.getProtectionDomain().getCodeSource().getLocation());
        if (location.contains("langgraph4j-mysql-saver")) {
            return "langgraph4j-mysql-saver";
        }
        if (location.contains("langgraph4j-sqlite-saver")) {
            return "langgraph4j-sqlite-saver";
        }
        if (location.contains("langgraph4j-postgres-saver")) {
            return "langgraph4j-postgres-saver";
        }
        String name = saver.getName();
        if (name.contains("MySQL")) {
            return "langgraph4j-mysql-saver";
        }
        if (name.contains("SQLite")) {
            return "langgraph4j-sqlite-saver";
        }
        return "langgraph4j-postgres-saver";
    }

    /**
     * 把临时目录插到系统类加载器前面。Saver 若使用另一个类加载器，也插一份，这样两种加载方式都能读到覆盖脚本。
     *
     * @param origin Saver 的类加载器，可以为空
     * @param url 临时 SQL 目录
     * @throws Exception 反射修改类路径失败时抛出
     */
    private static void install(ClassLoader origin, URL url) throws Exception {
        prepend(ClassLoader.getSystemClassLoader(), url);
        if (origin != null && origin != ClassLoader.getSystemClassLoader()) {
            prepend(origin, url);
        }
    }

    /**
     * 在类加载器的 URLClassPath 最前面插入覆盖目录，并卸掉这个加载器上一次留下的覆盖层。加载器没有 ucp 字段时不做修改。插入后清掉资源缓存。
     *
     * @param loader 要调整搜索顺序的类加载器
     * @param url 临时 SQL 目录
     * @throws Exception 反射调用失败时抛出
     */
    @SuppressWarnings("unchecked")
    private static void prepend(ClassLoader loader, URL url) throws Exception {
        Object ucp = classPath(loader);
        if (ucp == null) {
            return;
        }
        Method getLoader = ucp.getClass().getDeclaredMethod("getLoader", URL.class);
        getLoader.setAccessible(true);
        Object created = getLoader.invoke(ucp, url);
        Field loadersField = ucp.getClass().getDeclaredField("loaders");
        loadersField.setAccessible(true);
        List<Object> loaders = (List<Object>) loadersField.get(ucp);
        synchronized (ucp) {
            Object previous = overlays.get(loader);
            if (previous != null) {
                loaders.remove(previous);
            }
            loaders.add(0, created);
            overlays.put(loader, created);
        }
        clearCache(loader);
    }

    /**
     * 沿类加载器的继承链查找名为 ucp 的 URLClassPath。
     *
     * @param loader 起点类加载器
     * @return URLClassPath；整条继承链都没有该字段时为空
     * @throws Exception 读取字段失败时抛出
     */
    private static Object classPath(ClassLoader loader) throws Exception {
        Class<?> type = loader.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField("ucp");
                field.setAccessible(true);
                return field.get(loader);
            } catch (NoSuchFieldException ex) {
                type = type.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 清掉类加载器已经缓存的资源查找结果，让刚插入的 SQL 目录立刻可见。优先调用 clearCache，没有这个方法就把资源缓存置空。
     *
     * @param loader 刚被插入覆盖目录的类加载器
     */
    private static void clearCache(ClassLoader loader) {
        Class<?> type = loader.getClass();
        while (type != null) {
            try {
                Method clear = type.getDeclaredMethod("clearCache");
                clear.setAccessible(true);
                clear.invoke(loader);
                return;
            } catch (Exception ignored) {
                try {
                    Field cache = type.getDeclaredField("resourceCache");
                    cache.setAccessible(true);
                    cache.set(loader, null);
                    return;
                } catch (Exception missing) {
                    type = type.getSuperclass();
                }
            }
        }
    }
}
