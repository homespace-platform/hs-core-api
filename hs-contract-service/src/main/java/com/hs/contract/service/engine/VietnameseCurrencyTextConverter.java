package com.hs.contract.service.engine;

import java.math.BigDecimal;

public final class VietnameseCurrencyTextConverter {

    private static final String[] DIGITS = {
            "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    };

    private static final String[] UNITS = {
            "", "nghìn", "triệu", "tỷ", "nghìn tỷ", "triệu tỷ"
    };

    private VietnameseCurrencyTextConverter() {}

    public static String toWords(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return "Không đồng";
        }

        long value = amount.longValue();
        if (value < 0) {
            return "Âm " + convertPositiveNumber(Math.abs(value)) + " đồng";
        }

        String words = convertPositiveNumber(value);
        if (words.isEmpty()) {
            return "Không đồng";
        }

        // Viết hoa chữ cái đầu tiên
        words = Character.toUpperCase(words.charAt(0)) + words.substring(1) + " đồng chẵn";
        return words.replaceAll("\\s+", " ").trim();
    }

    private static String convertPositiveNumber(long number) {
        if (number == 0) return "không";

        StringBuilder result = new StringBuilder();
        int unitIndex = 0;

        while (number > 0) {
            long block = number % 1000;
            if (block > 0) {
                String blockText = convertThreeDigits((int) block, number >= 1000);
                String unit = UNITS[unitIndex];
                if (!unit.isEmpty()) {
                    result.insert(0, blockText + " " + unit + " ");
                } else {
                    result.insert(0, blockText + " ");
                }
            }
            number /= 1000;
            unitIndex++;
        }

        return result.toString().trim();
    }

    private static String convertThreeDigits(int number, boolean hasHigherBlock) {
        int hundreds = number / 100;
        int remainder = number % 100;
        int tens = remainder / 10;
        int ones = remainder % 10;

        StringBuilder sb = new StringBuilder();

        if (hundreds > 0 || hasHigherBlock) {
            sb.append(DIGITS[hundreds]).append(" trăm ");
        }

        if (tens > 1) {
            sb.append(DIGITS[tens]).append(" mươi ");
            if (ones == 1) {
                sb.append("mốt ");
            } else if (ones == 5) {
                sb.append("lăm ");
            } else if (ones > 0) {
                sb.append(DIGITS[ones]).append(" ");
            }
        } else if (tens == 1) {
            sb.append("mười ");
            if (ones == 5) {
                sb.append("lăm ");
            } else if (ones > 0) {
                sb.append(DIGITS[ones]).append(" ");
            }
        } else if (tens == 0 && ones > 0) {
            if (hundreds > 0 || hasHigherBlock) {
                sb.append("lẻ ");
            }
            sb.append(DIGITS[ones]).append(" ");
        }

        return sb.toString().trim();
    }
}
