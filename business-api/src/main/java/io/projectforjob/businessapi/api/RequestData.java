package io.projectforjob.businessapi.api;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

public final class RequestData {

    private RequestData() {
    }

    public static String text(JsonNode node, int max) {
        if (node == null || !node.isString()) {
            throw ApiFailure.invalid();
        }
        var value = node.asString().strip();
        if (value.length() > max) {
            throw ApiFailure.invalid();
        }
        return value;
    }

    public static String requiredText(JsonNode node, int max) {
        var value = text(node, max);
        if (value.isEmpty()) {
            throw ApiFailure.invalid();
        }
        return value;
    }

    public static long id(String value) {
        if (!value.matches("[1-9][0-9]{0,18}")) {
            throw ApiFailure.invalid();
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException overflow) {
            throw ApiFailure.invalid();
        }
    }

    public static void fields(ObjectNode body, String... allowed) {
        if (!Set.of(allowed).containsAll(body.propertyNames())) {
            throw ApiFailure.invalid();
        }
    }

    public static int version(ObjectNode body) {
        var value = body.get("expectedVersion");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 1) {
            throw ApiFailure.invalid();
        }
        return value.intValue();
    }

    public static String optionalText(JsonNode node, int max) {
        return node == null ? "" : text(node, max);
    }
}
