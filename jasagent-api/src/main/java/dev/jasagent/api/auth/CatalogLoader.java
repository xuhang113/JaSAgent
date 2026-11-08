package dev.jasagent.api.auth;

import java.nio.file.Path;
import java.util.concurrent.CompletionStage;

public interface CatalogLoader {
    CompletionStage<Catalog> load(Path workspaceDirectory, Path userDirectory);
}
