package dev.jasagent.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Grounding {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|「([^」]+)」");

    private Grounding() {
    }

    public static List<String> unmatched(String answer, String corpus) {
        List<String> missing = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            return missing;
        }
        String haystack = corpus == null ? "" : corpus;
        Matcher numbers = NUMBER.matcher(answer);
        while (numbers.find()) {
            if (!haystack.contains(numbers.group())) {
                missing.add(numbers.group());
            }
        }
        Matcher quotes = QUOTED.matcher(answer);
        while (quotes.find()) {
            String token = quotes.group(1) != null ? quotes.group(1) : quotes.group(2);
            if (token != null && !haystack.contains(token)) {
                missing.add(token);
            }
        }
        return missing;
    }
}
