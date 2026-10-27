package com.example.na.order.assistant.service;

import java.util.Locale;

final class TextNormalizer {

    private TextNormalizer() {
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character >= '\uFF01' && character <= '\uFF5E') {
                character = (char) (character - 0xFEE0);
            } else if (character == '\u3000') {
                character = ' ';
            }
            builder.append(character);
        }
        return builder.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }
}
