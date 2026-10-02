package net.vivans.dcim.module.job.domain;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Limited arithmetic grammar for operator-defined metrics; never executes application code. */
public final class FormulaExpression {
    private final String expression;
    private final Map<String, Double> values;
    private final Set<String> referenced = new HashSet<>();
    private int offset;

    private FormulaExpression(String expression, Map<String, Double> values) {
        if (expression == null || expression.isBlank() || expression.length() > 500) {
            throw new IllegalArgumentException("formula must contain 1-500 characters");
        }
        this.expression = expression;
        this.values = values;
    }

    public static Set<String> references(String expression) {
        FormulaExpression parser = new FormulaExpression(expression, null);
        parser.parse();
        return Set.copyOf(parser.referenced);
    }

    public static double evaluate(String expression, Map<String, Double> values) {
        if (values == null) throw new IllegalArgumentException("formula values are required");
        FormulaExpression parser = new FormulaExpression(expression, values);
        double result = parser.parse();
        if (!Double.isFinite(result)) throw new IllegalArgumentException("formula result is not finite");
        return result;
    }

    private double parse() {
        double result = sum();
        space();
        if (offset != expression.length()) throw invalid();
        if (referenced.isEmpty()) throw new IllegalArgumentException("formula must reference a source");
        return result;
    }

    private double sum() {
        double result = product();
        while (true) {
            space();
            if (take('+')) result += product();
            else if (take('-')) result -= product();
            else return result;
        }
    }

    private double product() {
        double result = atom();
        while (true) {
            space();
            if (take('*')) result *= atom();
            else if (take('/')) {
                double divisor = atom();
                if (divisor == 0D) {
                    if (values != null) throw new IllegalArgumentException("formula divides by zero");
                    result = 1D;
                } else result /= divisor;
            } else return result;
        }
    }

    private double atom() {
        space();
        if (take('+')) return atom();
        if (take('-')) return -atom();
        if (take('(')) {
            double result = sum();
            space();
            if (!take(')')) throw invalid();
            return result;
        }
        int start = offset;
        if (offset < expression.length() && Character.isLetter(expression.charAt(offset))) {
            offset++;
            while (offset < expression.length()) {
                char c = expression.charAt(offset);
                if (Character.isLetterOrDigit(c) || c == '_') offset++;
                else break;
            }
            String alias = expression.substring(start, offset);
            referenced.add(alias);
            if (values == null) return 1D;
            Double value = values.get(alias);
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("missing formula source: " + alias);
            }
            return value;
        }
        boolean digit = false;
        while (offset < expression.length() && Character.isDigit(expression.charAt(offset))) { offset++; digit = true; }
        if (take('.')) while (offset < expression.length() && Character.isDigit(expression.charAt(offset))) { offset++; digit = true; }
        if (!digit) throw invalid();
        return Double.parseDouble(expression.substring(start, offset));
    }

    private void space() {
        while (offset < expression.length() && Character.isWhitespace(expression.charAt(offset))) offset++;
    }

    private boolean take(char expected) {
        if (offset < expression.length() && expression.charAt(offset) == expected) { offset++; return true; }
        return false;
    }

    private IllegalArgumentException invalid() {
        return new IllegalArgumentException("invalid formula at position " + offset);
    }
}
