package com.agentloop.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
@Slf4j
public class MathTool {

    private static final Pattern SAFE_CHARS = Pattern.compile("^[\\d\\s+\\-*/().%]+$");

    @Tool(name = "calculator", description = "Evaluate a safe math expression")
    public String evaluate(@ToolParam(description = "math expression, e.g. 2+2 or (3*4)-1") String expression) {
        log.info("MathTool invoked");
        if (expression == null || !SAFE_CHARS.matcher(expression).matches()) {
            return "[error] invalid characters in expression";
        }
        try {
            // Strip whitespace up front — the parser works on a contiguous string
            String normalized = expression.replaceAll("\\s+", "");
            double result = parseExpression(normalized, new int[]{0});
            if (Double.isNaN(result) || Double.isInfinite(result)) {
                return "[error] non-finite result";
            }
            return formatResult(result);
        } catch (ArithmeticException e) {
            return "[error] " + e.getMessage();
        } catch (NumberFormatException e) {
            return "[error] malformed number";
        } catch (Exception e) {
            return "[error] " + e.getMessage();
        }
    }

    private String formatResult(double result) {
        if (result == Math.floor(result) && !Double.isInfinite(result)
                && Math.abs(result) < 1e15) {
            return String.valueOf((long) result);
        }
        return String.valueOf(result);
    }

    private double parseExpression(String s, int[] pos) {
        double left = parseTerm(s, pos);
        while (pos[0] < s.length()) {
            char op = s.charAt(pos[0]);
            if (op != '+' && op != '-') break;
            pos[0]++;
            double right = parseTerm(s, pos);
            left = (op == '+') ? left + right : left - right;
        }
        return left;
    }

    private double parseTerm(String s, int[] pos) {
        double left = parseFactor(s, pos);
        while (pos[0] < s.length()) {
            char op = s.charAt(pos[0]);
            if (op != '*' && op != '/' && op != '%') break;
            pos[0]++;
            double right = parseFactor(s, pos);
            if (op == '/') {
                if (right == 0) throw new ArithmeticException("division by zero");
                left = left / right;
            } else if (op == '%') {
                if (right == 0) throw new ArithmeticException("modulo by zero");
                left = left % right;
            } else {
                left = left * right;
            }
        }
        return left;
    }

    private double parseFactor(String s, int[] pos) {
        if (pos[0] >= s.length()) {
            throw new RuntimeException("unexpected end of expression");
        }

        // Handle unary +/- (e.g. -5, --3, -(2+3))
        double sign = 1;
        while (pos[0] < s.length() && (s.charAt(pos[0]) == '+' || s.charAt(pos[0]) == '-')) {
            if (s.charAt(pos[0]) == '-') sign = -sign;
            pos[0]++;
        }

        if (pos[0] >= s.length()) {
            throw new RuntimeException("unexpected end of expression");
        }

        char c = s.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            double val = parseExpression(s, pos) * sign;
            if (pos[0] >= s.length() || s.charAt(pos[0]) != ')') {
                throw new RuntimeException("missing closing parenthesis");
            }
            pos[0]++;
            return val;
        }
        int start = pos[0];
        while (pos[0] < s.length() && (Character.isDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        if (start == pos[0]) {
            throw new RuntimeException("unexpected character at position " + pos[0]);
        }
        return Double.parseDouble(s.substring(start, pos[0])) * sign;
    }
}
