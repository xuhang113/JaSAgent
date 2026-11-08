package dev.jasagent.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class Retrieval {
    static final double K1 = 1.2;
    static final double B = 0.75;

    private Retrieval() {
    }

    static List<Ranked> lexical(String query, List<IndexedChunk> chunks) {
        List<String> queryTokens = Tokenizer.tokens(query);
        if (chunks.isEmpty() || queryTokens.isEmpty()) {
            return List.of();
        }
        List<List<String>> docs = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            docs.add(Tokenizer.tokens(chunk.content()));
        }
        int total = docs.size();
        double average = 0;
        for (List<String> doc : docs) {
            average += doc.size();
        }
        average /= total;
        if (average == 0) {
            return List.of();
        }
        Map<String, Integer> documentFrequency = new HashMap<>();
        for (List<String> doc : docs) {
            for (String term : new HashSet<>(doc)) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }
        List<Ranked> ranked = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            Map<String, Integer> termFrequency = new HashMap<>();
            for (String term : docs.get(i)) {
                termFrequency.merge(term, 1, Integer::sum);
            }
            int length = docs.get(i).size();
            double score = 0;
            for (String term : queryTokens) {
                int frequency = termFrequency.getOrDefault(term, 0);
                if (frequency == 0) {
                    continue;
                }
                int docsWithTerm = documentFrequency.getOrDefault(term, 0);
                double idf = Math.log(1 + (total - docsWithTerm + 0.5) / (docsWithTerm + 0.5));
                double denominator = frequency + K1 * (1 - B + B * length / average);
                score += idf * (frequency * (K1 + 1)) / denominator;
            }
            if (score > 0) {
                ranked.add(new Ranked(chunks.get(i).chunkId(), score));
            }
        }
        return sort(ranked);
    }

    static List<Ranked> vector(String query, List<IndexedChunk> chunks, Embedder embedder) {
        if (chunks.isEmpty() || Tokenizer.tokens(query).isEmpty()) {
            return List.of();
        }
        float[] queryVector = embedder.embed(query);
        List<Ranked> ranked = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            float[] docVector = embedder.embed(chunk.content());
            double cosine = cosine(queryVector, docVector);
            if (cosine > 0) {
                ranked.add(new Ranked(chunk.chunkId(), cosine));
            }
        }
        return sort(ranked);
    }

    static List<Ranked> title(String query, List<IndexedChunk> chunks) {
        Set<String> queryTokens = new LinkedHashSet<>(Tokenizer.tokens(query));
        if (chunks.isEmpty() || queryTokens.isEmpty()) {
            return List.of();
        }
        List<Ranked> ranked = new ArrayList<>();
        for (IndexedChunk chunk : chunks) {
            Set<String> haystack = new HashSet<>(Tokenizer.tokens(chunk.title() + " " + chunk.uri()));
            int matched = 0;
            for (String token : queryTokens) {
                if (haystack.contains(token)) {
                    matched++;
                }
            }
            if (matched > 0) {
                ranked.add(new Ranked(chunk.chunkId(), matched));
            }
        }
        return sort(ranked);
    }

    private static double cosine(float[] left, float[] right) {
        int length = Math.min(left.length, right.length);
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < length; i++) {
            dot += (double) left[i] * right[i];
            leftNorm += (double) left[i] * left[i];
            rightNorm += (double) right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static List<Ranked> sort(List<Ranked> ranked) {
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed().thenComparing(Ranked::chunkId));
        return ranked;
    }
}
