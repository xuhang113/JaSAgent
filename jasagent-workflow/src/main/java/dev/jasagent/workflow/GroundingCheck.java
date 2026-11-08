package dev.jasagent.workflow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class GroundingCheck {
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"|“([^”]+)”|'([^']+)'");

    private GroundingCheck() {
    }

    static boolean grounded(String answer, String evidence) {
        if (answer == null || answer.isBlank()) {
            return true;
        }
        String source = evidence == null ? "" : evidence;
        Matcher numbers = NUMBER.matcher(answer);
        while (numbers.find()) {
            if (!source.contains(numbers.group())) {
                return false;
            }
        }
        Matcher quotes = QUOTED.matcher(answer);
        while (quotes.find()) {
            String fragment = quotes.group(1) != null ? quotes.group(1) : quotes.group(2) != null ? quotes.group(2) : quotes.group(3);
            if (fragment != null && !fragment.isBlank() && !source.contains(fragment)) {
                return false;
            }
        }
        return true;
    }
}
