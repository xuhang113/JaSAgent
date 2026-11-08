package dev.jasagent.plugin;

import java.util.List;

public interface WebSearchClient {
    List<SearchHit> search(String query);
}
