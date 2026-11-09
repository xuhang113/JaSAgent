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

final class SaverResources {
    static final Object LOCK = new Object();

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
    private static final Map<ClassLoader, Object> overlays = new IdentityHashMap<>();

    private SaverResources() {
    }

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

    private static Path directory() throws Exception {
        if (root == null) {
            root = Files.createTempDirectory("jasagent-saver-sql");
        }
        return root;
    }

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

    private static InputStream open(ClassLoader origin, String token, String name) throws Exception {
        InputStream found = open(origin == null ? null : origin.getResources(name), token);
        if (found != null) {
            return found;
        }
        return open(ClassLoader.getSystemClassLoader().getResources(name), token);
    }

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

    private static void install(ClassLoader origin, URL url) throws Exception {
        prepend(ClassLoader.getSystemClassLoader(), url);
        if (origin != null && origin != ClassLoader.getSystemClassLoader()) {
            prepend(origin, url);
        }
    }

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
