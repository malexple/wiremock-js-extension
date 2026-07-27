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

public class WiremockJsInterpreter {

    private final RequestFacade requestFacade;

    public WiremockJsInterpreter(RequestFacade requestFacade) {
        this.requestFacade = requestFacade;
    }

    public Map<String, Object> execute(String source) {
        WiremockJsParser.ScriptContext tree = parse(source);
        Visitor visitor = new Visitor();
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

        // Function-scoped хранилище переменных — единая область видимости на весь скрипт,
        // общая для всех веток if/else. Block-scoped (let/const) — предмет отдельной фазы.
        private final Map<String, Object> scope = new HashMap<>();

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
                case "contains":
                    return str(args, 0) != null && str(args, 0).contains(str(args, 1));
                default:
                    throw new ScriptExecutionException("Функция не разрешена или не существует: " + name);
            }
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
            double left = num(List.of(visit(ctx.expression(0))), 0);
            double right = num(List.of(visit(ctx.expression(1))), 0);
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
                eq = num(List.of(left), 0) == num(List.of(right), 0);
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
            return toBoolean(visit(ctx.expression(0))) && toBoolean(visit(ctx.expression(1)));
        }

        @Override
        public Object visitLogicalOr(WiremockJsParser.LogicalOrContext ctx) {
            return toBoolean(visit(ctx.expression(0))) || toBoolean(visit(ctx.expression(1)));
        }

        @Override
        public Object visitLogicalNot(WiremockJsParser.LogicalNotContext ctx) {
            return !toBoolean(visit(ctx.expression()));
        }

        @Override
        public Object visitAddSub(WiremockJsParser.AddSubContext ctx) {
            double left = num(List.of(visit(ctx.expression(0))), 0);
            double right = num(List.of(visit(ctx.expression(1))), 0);
            return ctx.op.getText().equals("+") ? left + right : left - right;
        }

        @Override
        public Object visitMulDiv(WiremockJsParser.MulDivContext ctx) {
            double left = num(List.of(visit(ctx.expression(0))), 0);
            double right = num(List.of(visit(ctx.expression(1))), 0);
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
            Object val = args.get(i);
            if (val instanceof Number) {
                return ((Number) val).doubleValue();
            }
            if (val instanceof String) {
                try {
                    return Double.parseDouble((String) val);
                } catch (NumberFormatException e) {
                    return 0;
                }
            }
            return 0;
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