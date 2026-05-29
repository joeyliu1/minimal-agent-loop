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
            return String.valueOf(parseExpression(expression.trim(), new int[]{0}));
        } catch (Exception e) {
            return "[error] " + e.getMessage();
        }
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
            if (op != '*' && op != '/') break;
            pos[0]++;
            double right = parseFactor(s, pos);
            if (op == '/') left = left / right;
            else left = left * right;
        }
        return left;
    }

    private double parseFactor(String s, int[] pos) {
        if (pos[0] >= s.length()) return 0;
        char c = s.charAt(pos[0]);
        if (c == '(') {
            pos[0]++;
            double val = parseExpression(s, pos);
            if (pos[0] < s.length() && s.charAt(pos[0]) == ')') pos[0]++;
            return val;
        }
        int start = pos[0];
        while (pos[0] < s.length() && (Character.isDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        return Double.parseDouble(s.substring(start, pos[0]));
    }
}