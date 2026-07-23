grammar WiremockJs;

script
    : statement+ EOF
    ;

statement
    : ifStatement
    | returnStatement
    ;

ifStatement
    : IF '(' expression ')' '{' thenStmt+=statement+ '}' (ELSE '{' elseStmt+=statement+ '}')?
    ;

returnStatement
    : RETURN expression ';'
    ;

expression
    : expression op=('*'|'/'|'%') expression        # MulDiv
    | expression op=('+'|'-') expression            # AddSub
    | expression op=('>'|'>='|'<'|'<=') expression  # Compare
    | expression op=('=='|'!=') expression          # Equality
    | expression AND expression                     # LogicalAnd
    | expression OR expression                      # LogicalOr
    | NOT expression                                # LogicalNot
    | functionCall                                  # FuncCallExpr
    | fieldAccess                                   # FieldAccessExpr
    | literal                                       # LiteralExpr
    | '(' expression ')'                            # ParenExpr
    ;

functionCall
    : IDENTIFIER '(' argumentList? ')'
    ;

argumentList
    : expression (',' expression)*
    ;

fieldAccess
    : IDENTIFIER ('.' IDENTIFIER)*
    ;

literal
    : STRING
    | NUMBER
    | BOOLEAN
    | jsonObject
    ;

jsonObject
    : '{' (jsonPair (',' jsonPair)*)? '}'
    ;

jsonPair
    : STRING ':' expression
    ;

IF: 'if';
ELSE: 'else';
RETURN: 'return';
AND: '&&';
OR: '||';
NOT: '!';
BOOLEAN: 'true' | 'false';
NUMBER: '-'? [0-9]+ ('.' [0-9]+)?;
STRING: '"' (~["\\] | '\\' .)* '"';
IDENTIFIER: [a-zA-Z_][a-zA-Z0-9_]*;
WS: [ \t\r\n]+ -> skip;
COMMENT: '//' ~[\r\n]* -> skip;