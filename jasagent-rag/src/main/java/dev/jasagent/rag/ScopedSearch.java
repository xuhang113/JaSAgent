package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ScopedSearch {
    private final Corpus corpus;

    public ScopedSearch(Corpus corpus) {
        this.corpus = corpus;
    }

    public RagResult search(String query, List<String> scopes) {
        List<Hit> hits = new ArrayList<>();
        String needle = query.toLowerCase(Locale.ROOT);
        for (Chunk chunk : corpus.chunks()) {
            if (!scopes.contains(chunk.scope())) {
                continue;
            }
            if (!chunk.text().toLowerCase(Locale.ROOT).contains(needle) && !sharesToken(chunk.text(), needle)) {
                continue;
            }
            hits.add(new Hit(
                    chunk.scope(),
                    chunk.uri(),
                    chunk.docId(),
                    chunk.chunkId(),
                    chunk.start(),
                    chunk.end(),
                    Corpus.hash(chunk.text()),
                    chunk.text(),
                    1.0,
                    1.0));
        }
        hits.sort(Comparator.comparing(Hit::chunkId));
        if (hits.isEmpty()) {
            return new RagResult(List.of(), "quality-empty");
        }
        return new RagResult(hits, null);
    }

    private static boolean sharesToken(String text, String query) {
        for (String token : query.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank() && text.toLowerCase(Locale.ROOT).contains(token)) {
                return true;
            }
        }
        return false;
    }
}
