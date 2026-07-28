package ru.mcs.wiremockjs.interpreter;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import ru.mcs.wiremockjs.exception.ScriptExecutionException;
import ru.mcs.wiremockjs.exception.ScriptParseException;
import ru.mcs.wiremockjs.grammar.WiremockJsLexer;
import ru.mcs.wiremockjs.grammar.WiremockJsParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Random;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import net.datafaker.Faker;

public class WiremockJsInterpreter {

    private final RequestFacade requestFacade;

    public WiremockJsInterpreter(RequestFacade requestFacade) {
        this.requestFacade = requestFacade;
    }

    public Map<String, Object> execute(String source) {
        return execute(source, null);
    }

    public Map<String, Object> execute(String source, Integer seed) {
        WiremockJsParser.ScriptContext tree = parse(source);
        Random random = seed != null ? new Random(seed) : new Random();
        Faker faker = new Faker(random);
        Visitor visitor = new Visitor(random, faker);
        return visitor.visitScript(tree);
    }

    private WiremockJsParser.ScriptContext parse(String source) {
        try {
            WiremockJsLexer lexer = new WiremockJsLexer(CharStreams.fromString(source));
            CommonTokenStream tokens = new CommonTokenStream(lexer);
            WiremockJsParser parser = new WiremockJsParser(tokens);
            parser.removeErrorListeners();
            parser.addErrorListener(new ThrowingErrorListener());
            return parser.script();
        } catch (Exception e) {
            throw new ScriptParseException("Ошибка разбора скрипта: " + e.getMessage(), e);
        }
    }

    private class Visitor extends ru.mcs.wiremockjs.grammar.WiremockJsBaseVisitor<Object> {

        private Map<String, Object> returnedValue = null;
        private final Map<String, Object> scope = new HashMap<>();
        private final Random random;
        private final Faker faker;

        Visitor(Random random, Faker faker) {
            this.random = random;
            this.faker = faker;
        }

        public Map<String, Object> visitScript(WiremockJsParser.ScriptContext ctx) {
            for (WiremockJsParser.StatementContext stmt : ctx.statement()) {
                visit(stmt);
                if (returnedValue != null) {
                    return returnedValue;
                }
            }
            throw new ScriptExecutionException("Скрипт завершился без оператора return");
        }

        @Override
        public Object visitVarDeclaration(WiremockJsParser.VarDeclarationContext ctx) {
            String name = ctx.IDENTIFIER().getText();
            Object value = visit(ctx.expression());
            scope.put(name, value);
            return null;
        }

        @Override
        public Object visitVarRefExpr(WiremockJsParser.VarRefExprContext ctx) {
            String name = ctx.getText();
            if (!scope.containsKey(name)) {
                throw new ScriptExecutionException("Переменная не объявлена: " + name);
            }
            return scope.get(name);
        }

        @Override
        public Object visitIfStatement(WiremockJsParser.IfStatementContext ctx) {
            Object condition = visit(ctx.expression());
            boolean isTrue = toBoolean(condition);

            List<WiremockJsParser.StatementContext> branch = isTrue
                    ? ctx.thenStmt
                    : ctx.elseStmt;

            if (branch == null) {
                return null;
            }

            for (WiremockJsParser.StatementContext stmt : branch) {
                visit(stmt);
                if (returnedValue != null) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public Object visitReturnStatement(WiremockJsParser.ReturnStatementContext ctx) {
            Object value = visit(ctx.expression());
            if (value instanceof Map) {
                returnedValue = (Map<String, Object>) value;
            } else {
                Map<String, Object> wrapped = new LinkedHashMap<>();
                wrapped.put("value", value);
                returnedValue = wrapped;
            }
            return null;
        }

        @Override
        public Object visitFuncCallExpr(WiremockJsParser.FuncCallExprContext ctx) {
            String funcName = ctx.functionCall().IDENTIFIER().getText();
            List<Object> args;
            if (ctx.functionCall().argumentList() == null) {
                args = List.of();
            } else {
                args = ctx.functionCall().argumentList().expression().stream()
                        .map(this::visit)
                        .collect(java.util.stream.Collectors.toList());
            }
            return callWhitelistedFunction(funcName, args);
        }

        private Object callWhitelistedFunction(String name, List<Object> args) {
            switch (name) {
                case "query": return requestFacade.query(str(args, 0));
                case "header": return requestFacade.header(str(args, 0));
                case "body": return requestFacade.body();
                case "method": return requestFacade.method();
                case "pathSegment": return requestFacade.pathSegment((int) num(args, 0));
                case "jsonField": return requestFacade.jsonField(str(args, 0));
                case "contains": {
                    String haystack = str(args, 0);
                    String needle = str(args, 1);
                    if (haystack == null || needle == null) {
                        return false;
                    }
                    return haystack.contains(needle);
                }
                case "random": return random.nextDouble();
                case "now": return Instant.now().toString();
                case "nowPlusDays": return Instant.now().plus((long) num(args, 0), ChronoUnit.DAYS).toString();
                case "uuid": return generateUuid();
                case "randomInt": return (double) randomInt((int) num(args, 0), (int) num(args, 1));
                case "matches": {
                    String input = str(args, 0);
                    String pattern = str(args, 1);
                    if (input == null || pattern == null) {
                        return false;
                    }
                    try {
                        return com.google.re2j.Pattern.matches(pattern, input);
                    } catch (com.google.re2j.PatternSyntaxException e) {
                        throw new ScriptExecutionException("Некорректное регулярное выражение: " + e.getMessage());
                    }
                }
                case "fake": {
                    String pattern = str(args, 0);
                    if (pattern == null) {
                        throw new ScriptExecutionException("fake(): шаблон не может быть null");
                    }
                    try {
                        return faker.expression(pattern);
                    } catch (Exception e) {
                        throw new ScriptExecutionException("Ошибка в шаблоне fake(\"" + pattern + "\"): " + e.getMessage());
                    }
                }
                case "sum": return sumOf(args.get(0));
                case "count": return (double) toList(args.get(0)).size();
                case "avg": {
                    List<Object> list = toList(args.get(0));
                    if (list.isEmpty()) {
                        throw new ScriptExecutionException("avg(): пустой массив");
                    }
                    return sumOf(args.get(0)) / list.size();
                }
                case "mapKeys": return mapKeys(toList(args.get(0)), toMap(args.get(1)));
                default:
                    throw new ScriptExecutionException("Функция не разрешена или не существует: " + name);
            }
        }

        private int randomInt(int min, int max) {
            if (min > max) {
                throw new ScriptExecutionException(
                        "randomInt(): min (" + min + ") не может быть больше max (" + max + ")");
            }
            return random.nextInt(max - min + 1) + min;
        }

        private String generateUuid() {
            byte[] randomBytes = new byte[16];
            random.nextBytes(randomBytes);

            randomBytes[6] &= 0x0f;
            randomBytes[6] |= 0x40;
            randomBytes[8] &= 0x3f;
            randomBytes[8] |= (byte) 0x80;

            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) msb = (msb << 8) | (randomBytes[i] & 0xff);
            for (int i = 8; i < 16; i++) lsb = (lsb << 8) | (randomBytes[i] & 0xff);

            return new UUID(msb, lsb).toString();
        }

        @Override
        public Object visitFieldAccessExpr(WiremockJsParser.FieldAccessExprContext ctx) {
            List<org.antlr.v4.runtime.tree.TerminalNode> identifiers = ctx.fieldAccess().IDENTIFIER();
            String baseName = identifiers.get(0).getText();

            if (!scope.containsKey(baseName)) {
                throw new ScriptExecutionException("Переменная не объявлена: " + baseName);
            }

            Object current = scope.get(baseName);

            for (int i = 1; i < identifiers.size(); i++) {
                String segment = identifiers.get(i).getText();

                if (current == null) {
                    return null;
                }
                if (!(current instanceof Map)) {
                    throw new ScriptExecutionException(
                            "Невозможно прочитать поле '" + segment + "' у не-объекта (тип: "
                                    + current.getClass().getSimpleName() + ")");
                }

                current = ((Map<?, ?>) current).get(segment);
            }

            return current;
        }

        @Override
        public Object visitLiteralExpr(WiremockJsParser.LiteralExprContext ctx) {
            return visit(ctx.literal());
        }

        @Override
        public Object visitJsonObject(WiremockJsParser.JsonObjectContext ctx) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (WiremockJsParser.JsonPairContext pair : ctx.jsonPair()) {
                String key = unquote(pair.STRING().getText());
                Object value = visit(pair.expression());
                map.put(key, value);
            }
            return map;
        }

        @Override
        public Object visitLiteral(WiremockJsParser.LiteralContext ctx) {
            if (ctx.STRING() != null) {
                return unquote(ctx.STRING().getText());
            }
            if (ctx.NUMBER() != null) {
                String text = ctx.NUMBER().getText();
                double value = Double.parseDouble(text);
                if (value == Math.floor(value) && !text.contains(".")) {
                    return (long) value;
                }
                return value;
            }
            if (ctx.BOOLEAN() != null) {
                return Boolean.parseBoolean(ctx.BOOLEAN().getText());
            }
            if (ctx.NULL() != null) {
                return null;
            }
            if (ctx.jsonObject() != null) {
                return visit(ctx.jsonObject());
            }
            throw new ScriptExecutionException("Не удалось разобрать литерал: " + ctx.getText());
        }

        @Override
        public Object visitCompare(WiremockJsParser.CompareContext ctx) {
            double left = num(visit(ctx.expression(0)));
            double right = num(visit(ctx.expression(1)));
            String op = ctx.op.getText();
            if (op.equals(">")) return left > right;
            if (op.equals(">=")) return left >= right;
            if (op.equals("<")) return left < right;
            if (op.equals("<=")) return left <= right;
            throw new ScriptExecutionException("Неизвестный оператор сравнения");
        }

        @Override
        public Object visitEquality(WiremockJsParser.EqualityContext ctx) {
            Object left = visit(ctx.expression(0));
            Object right = visit(ctx.expression(1));
            boolean eq;
            if (left == null || right == null) {
                eq = left == right;
            } else if (isNumeric(left) && isNumeric(right)) {
                eq = num(left) == num(right);
            } else {
                eq = String.valueOf(left).equals(String.valueOf(right));
            }
            return ctx.op.getText().equals("==") ? eq : !eq;
        }

        private boolean isNumeric(Object val) {
            if (val instanceof Number) {
                return true;
            }
            if (val instanceof String) {
                return ((String) val).matches("-?\\d+(\\.\\d+)?");
            }
            return false;
        }

        @Override
        public Object visitLogicalAnd(WiremockJsParser.LogicalAndContext ctx) {
            if (!toBoolean(visit(ctx.expression(0)))) {
                return false;
            }
            return toBoolean(visit(ctx.expression(1)));
        }

        @Override
        public Object visitLogicalOr(WiremockJsParser.LogicalOrContext ctx) {
            if (toBoolean(visit(ctx.expression(0)))) {
                return true;
            }
            return toBoolean(visit(ctx.expression(1)));
        }

        @Override
        public Object visitLogicalNot(WiremockJsParser.LogicalNotContext ctx) {
            return !toBoolean(visit(ctx.expression()));
        }

        @Override
        public Object visitAddSub(WiremockJsParser.AddSubContext ctx) {
            double left = num(visit(ctx.expression(0)));
            double right = num(visit(ctx.expression(1)));
            return ctx.op.getText().equals("+") ? left + right : left - right;
        }

        @Override
        public Object visitMulDiv(WiremockJsParser.MulDivContext ctx) {
            double left = num(visit(ctx.expression(0)));
            double right = num(visit(ctx.expression(1)));
            String op = ctx.op.getText();
            if (op.equals("*")) return left * right;
            if (op.equals("/")) return left / right;
            if (op.equals("%")) return left % right;
            throw new ScriptExecutionException("Неизвестный арифметический оператор");
        }

        @Override
        public Object visitParenExpr(WiremockJsParser.ParenExprContext ctx) {
            return visit(ctx.expression());
        }

        private String str(List<Object> args, int i) {
            return i < args.size() && args.get(i) != null ? String.valueOf(args.get(i)) : null;
        }

        private double num(List<Object> args, int i) {
            return num(args.get(i));
        }

        private double num(Object val) {
            if (val == null) {
                throw new ScriptExecutionException("Ожидалось число, но получено null");
            }
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
            if (val instanceof String) {
                try {
                    return Double.parseDouble((String) val);
                } catch (NumberFormatException e) {
                    throw new ScriptExecutionException(
                            "Не удалось преобразовать строку в число: \"" + val + "\"");
                }
            }
            throw new ScriptExecutionException(
                    "Ожидалось число, но получен тип: " + val.getClass().getSimpleName());
        }

        private boolean toBoolean(Object val) {
            if (val instanceof Boolean) {
                return (Boolean) val;
            }
            if (val instanceof Number) {
                return ((Number) val).doubleValue() != 0;
            }
            return val != null;
        }

        private String unquote(String raw) {
            return raw.substring(1, raw.length() - 1);
        }

        @SuppressWarnings("unchecked")
        private List<Object> toList(Object val) {
            if (!(val instanceof List)) {
                throw new ScriptExecutionException(
                        "Ожидался массив, получен: " + (val == null ? "null" : val.getClass().getSimpleName()));
            }
            return (List<Object>) val;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> toMap(Object val) {
            if (!(val instanceof Map)) {
                throw new ScriptExecutionException(
                        "Ожидался объект, получен: " + (val == null ? "null" : val.getClass().getSimpleName()));
            }
            return (Map<String, Object>) val;
        }

        @Override
        public Object visitForEachStatement(WiremockJsParser.ForEachStatementContext ctx) {
            String iteratorName = ctx.IDENTIFIER().getText();
            Object iterableObj = visit(ctx.expression());
            List<Object> itemsList = toList(iterableObj);

            boolean hadPreviousValue = scope.containsKey(iteratorName);
            Object oldVariableValue = scope.get(iteratorName);

            try {
                for (Object item : itemsList) {
                    scope.put(iteratorName, item);
                    for (WiremockJsParser.StatementContext stmt : ctx.statement()) {
                        visit(stmt);
                        if (returnedValue != null) {
                            return null;
                        }
                    }
                }
            } finally {
                if (hadPreviousValue) {
                    scope.put(iteratorName, oldVariableValue);
                } else {
                    scope.remove(iteratorName);
                }
            }
            return null;
        }

        private double sumOf(Object val) {
            List<Object> list = toList(val);
            double total = 0;
            for (Object item : list) {
                total += num(item);
            }
            return total;
        }

        private List<Object> mapKeys(List<Object> array, Map<String, Object> keyMapping) {
            List<Object> result = new java.util.ArrayList<>();
            for (Object item : array) {
                if (!(item instanceof Map)) {
                    throw new ScriptExecutionException(
                            "mapKeys(): элемент массива не является объектом (тип: "
                                    + (item == null ? "null" : item.getClass().getSimpleName()) + ")");
                }
                Map<?, ?> itemMap = (Map<?, ?>) item;
                Map<String, Object> newMap = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : itemMap.entrySet()) {
                    String oldKey = String.valueOf(entry.getKey());
                    String newKey = keyMapping.containsKey(oldKey)
                            ? String.valueOf(keyMapping.get(oldKey))
                            : oldKey;
                    newMap.put(newKey, entry.getValue());
                }
                result.add(newMap);
            }
            return result;
        }
    }

    private static class ThrowingErrorListener extends org.antlr.v4.runtime.BaseErrorListener {
        @Override
        public void syntaxError(org.antlr.v4.runtime.Recognizer<?, ?> recognizer, Object offendingSymbol,
                                int line, int charPositionInLine, String msg,
                                org.antlr.v4.runtime.RecognitionException e) {
            throw new ScriptParseException("Синтаксическая ошибка [строка " + line + ":" + charPositionInLine + "] " + msg);
        }
    }
}