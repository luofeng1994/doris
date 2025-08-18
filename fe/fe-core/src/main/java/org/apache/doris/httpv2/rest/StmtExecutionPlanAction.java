// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.httpv2.rest;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.commons.lang3.StringUtils;
import org.apache.doris.analysis.*;
import org.apache.doris.catalog.Env;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.util.SqlParserUtils;
import org.apache.doris.datasource.InternalCatalog;
import org.apache.doris.httpv2.entity.ResponseEntityBuilder;
import org.apache.doris.httpv2.util.StatementSubmitter;
import org.apache.doris.nereids.FLDorisLexer;
import org.apache.doris.nereids.FLDorisParser;
import org.apache.doris.nereids.FLDorisParserBaseVisitor;
import org.apache.doris.qe.ConnectContext;
import org.apache.doris.system.SystemInfoService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Serializable;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.*;


/**
 * For execute stmt or get create table stmt via http
 */
@RestController
public class StmtExecutionPlanAction extends RestBaseController {
    private static final Logger LOG = LogManager.getLogger(StmtExecutionPlanAction.class);
    private static StatementSubmitter stmtSubmitter = new StatementSubmitter();
    private static final String NEW_LINE_PATTERN = "[\n\r]";

    private static final String NEW_LINE_REPLACEMENT = " ";

    private static final long DEFAULT_ROW_LIMIT = 1000;
    private static final long MAX_ROW_LIMIT = 10000;
    private static String COLUMN_NAME_REGEX = "^[_a-zA-Z@0-9\\s<>/][.a-zA-Z0-9_+-/><?@#$%^&*\"\\s,:]{0,255}$";

    /**
     * Get all create table stmt of a SQL
     *
     * @param ns
     * @param dbName
     * @param request
     * @param response
     * @return plain text of create table stmts
     */
    @RequestMapping(path = "/api/query_plan/{" + NS_KEY + "}/{" + DB_KEY + "}", method = {RequestMethod.POST})
    public Object queryPlan(@PathVariable(value = NS_KEY) String ns, @PathVariable(value = DB_KEY) String dbName,
                            HttpServletRequest request, HttpServletResponse response, @RequestBody String body) {
        if (needRedirect(request.getScheme())) {
            return redirectToHttps(request);
        }

        checkWithCookie(request, response, false);

        Type type = new TypeToken<StmtRequestBody>() {
        }.getType();
        StmtRequestBody stmtRequestBody = new Gson().fromJson(body, type);

        String sql = stmtRequestBody.stmt;
        boolean validate = stmtRequestBody.sql_validate;
        boolean condition = stmtRequestBody.sql_condition;
        if (Strings.isNullOrEmpty(sql)) {
            return ResponseEntityBuilder.badRequest("Missing statement request body");
        }
        LOG.info("stmt: {}", sql);

        if (ns.equalsIgnoreCase(SystemInfoService.DEFAULT_CLUSTER)) {
            ns = InternalCatalog.INTERNAL_CATALOG_NAME;
        }
//        if (StringUtils.isNotBlank(sql)) {
//            sql = sql.replaceAll(NEW_LINE_PATTERN, NEW_LINE_REPLACEMENT);
//        }
        ConnectContext.get().changeDefaultCatalog(ns);
        ConnectContext.get().setDatabase(getFullDbName(dbName));
        if (validate) {
            return validateSql(sql);
        } else if (condition) {
            return conditionSql(sql);
        } else {
            return getSchema(sql);
        }
    }

    @NotNull
    private ResponseEntity getSchema(String sql) {
        SqlParser parser = new SqlParser(new SqlScanner(new StringReader(sql)));
        StatementBase stmt = null;
        try {
            stmt = SqlParserUtils.getStmt(parser, 0);
            if (!(stmt instanceof QueryStmt)) {
                return ResponseEntityBuilder.ok(-1);
            }

            Analyzer analyzer = new Analyzer(Env.getCurrentEnv(), ConnectContext.get());
            QueryStmt queryStmt = (QueryStmt) stmt;
            queryStmt.analyze(analyzer);
            ArrayList<String> colLabels = queryStmt.getColLabels();
            ArrayList<Expr> resultExprs = queryStmt.getResultExprs();
            List<Map<String, String>> metaFields = Lists.newArrayList();
            for (int i = 0; i < colLabels.size(); i++) {
                Map<String, String> field = Maps.newHashMap();
                field.put("name", colLabels.get(i).toLowerCase());

                String type = resultExprs.get(i).getType().toSql();
                field.put("type",
                    type.equalsIgnoreCase("time") || type.equalsIgnoreCase("timev2")
                        ? "string" : type);
                metaFields.add(field);
            }
            return ResponseEntityBuilder.ok(metaFields);
        } catch (AnalysisException e) {
            return getResponseEntity(e, parser, sql);
        } catch (Exception e) {
            return ResponseEntityBuilder.internalError(parser.getErrorMsg(sql));
        }
    }

    private ResponseEntity validateSql(String sql) {
        SqlParser parser = new SqlParser(new SqlScanner(new StringReader(sql)));
        StatementBase stmt = null;
        try {
            List<StatementBase> statements = SqlParserUtils.getMultiStmts(parser);
            // 1 禁止多个SQL
            if (statements == null || statements.size() > 1) {
                throw new AnalysisException("自定义SQL只支持单个查询语句");
            }
            stmt = statements.get(0);
            SelectStmt queryStmt;
            // 2 只允许执行select查询
            if (!(stmt instanceof QueryStmt)) {
                throw new AnalysisException("自定义SQL只支持select语句");
            }
            if (stmt instanceof SelectStmt) {
                queryStmt = (SelectStmt) stmt;
            } else {
                queryStmt = (SelectStmt) ((SetOperationStmt) stmt).getOperands().get(0).getQueryStmt();
            }
            // 3 禁止使用with xx as ( select * from xxx)
            if (queryStmt.getWithClause() != null) {
                throw new AnalysisException("自定义SQL不支持with as cte语法");
            }

            // 4 获取select字段后注释信息
            Map<String, String> descriptions = getFieldDescriptions(sql);
            // 5 select字段 获取 alias 判断是否空
            List<Map<String, String>> metaFields = Lists.newArrayList();
            for (SelectListItem item : queryStmt.getSelectList().getItems()) {
                String alias = item.getAlias();
                if (StringUtils.isBlank(alias)) {
                    throw new AnalysisException("自定义SQL中Select字段别名不能为空");
                }
                alias = alias.replace("`", "").toLowerCase();
                // 检查是否包含中文（循环遍历字符）
                boolean hasChinese = false;
                for (char c : alias.toCharArray()) {
                    if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                        hasChinese = true;
                        break;
                    }
                }
                if (hasChinese || !alias.matches(COLUMN_NAME_REGEX)) {
                    throw new AnalysisException("自定义SQL中Select字段别名不能包含中文及特殊符号");
                }

                Map<String, String> field = Maps.newHashMap();
                if (!descriptions.containsKey(alias)) {
                    throw new AnalysisException("请按照规范设置最外层所有查询字段的注释信息");
                }
                field.put("name", alias);
                field.put("comment", descriptions.get(alias));
                metaFields.add(field);
            }
            return ResponseEntityBuilder.ok(metaFields);
        } catch (AnalysisException e) {
            return getResponseEntity(e, parser, sql);
        } catch (Exception e) {
            return ResponseEntityBuilder.internalError(parser.getErrorMsg(sql));
        }
    }

    private ResponseEntity conditionSql(String sql) {
        SqlParser parser = new SqlParser(new SqlScanner(new StringReader(sql)));
        StatementBase stmt = null;
        try {
            List<StatementBase> statements = SqlParserUtils.getMultiStmts(parser);
            // 1 禁止多个SQL
            if (statements == null || statements.size() > 1) {
                throw new AnalysisException("自定义SQL只支持单个查询语句");
            }
            stmt = statements.get(0);
            SelectStmt queryStmt;
            // 2 只允许执行select查询
            if (!(stmt instanceof QueryStmt)) {
                throw new AnalysisException("自定义SQL只支持select语句");
            }
            if (stmt instanceof SelectStmt) {
                queryStmt = (SelectStmt) stmt;
            } else {
                queryStmt = (SelectStmt) ((SetOperationStmt) stmt).getOperands().get(0).getQueryStmt();
            }
            // 3 禁止使用with xx as ( select * from xxx)
            if (queryStmt.getWithClause() != null) {
                throw new AnalysisException("自定义SQL不支持with as cte语法");
            }
            // 4 获取where条件信息
            List<FilterCondition> conditions = getWhereConditions(sql);
            return ResponseEntityBuilder.ok(conditions);
        } catch (AnalysisException e) {
            return getResponseEntity(e, parser, sql);
        } catch (Exception e) {
            return ResponseEntityBuilder.internalError(parser.getErrorMsg(sql));
        }
    }
    @NotNull
    private static ResponseEntity getResponseEntity(AnalysisException e, SqlParser parser, String sql) {
        String errMsg = e.getMessage();
        String detailMessage = "detailMessage = ";
        // 判断是否包含detailMessage =
        if (errMsg.contains(detailMessage)) {
            errMsg = errMsg.substring(errMsg.indexOf(detailMessage) + detailMessage.length());
            if (errMsg.equals("Syntax error")) {
                return ResponseEntityBuilder.internalError(parser.getErrorMsg(sql));
            }
            return ResponseEntityBuilder.internalError(errMsg);
        }
        return ResponseEntityBuilder.internalError(errMsg);
    }

    public Map<String, String> getFieldDescriptions(String sql) throws AnalysisException {
        FLDorisLexer lexer = new FLDorisLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FLDorisParser flparser = new FLDorisParser(tokens);
        ParseTree tree = flparser.selectClause();
        Map<String, String> descriptions = new HashMap<>();
        try {
            SelectCommentExtractor visitor = new SelectCommentExtractor(tokens);
            visitor.visit(tree);
            descriptions = visitor.getFieldDescriptions();
        } catch (Exception e) {
            throw new AnalysisException("获取字段与注释信息报错," + e.getMessage());
        }
        return descriptions;
    }

    /**
     * 从SQL中提取WHERE条件
     * @param sql SQL语句
     * @return 过滤条件列表
     * @throws AnalysisException 解析异常
     */
    public List<FilterCondition> getWhereConditions(String sql) throws Exception {
        FLDorisLexer lexer = new FLDorisLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // // 过滤掉SELECT_FIELD_COMMENT token，避免影响WHERE条件解析
        tokens.fill();
        List<Token> filteredTokens = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            // 跳过SELECT_FIELD_COMMENT token
            if (token.getType() != FLDorisLexer.SELECT_FIELD_COMMENT) {
                filteredTokens.add(token);
            } else {
                System.out.println("SELECT_FIELD_COMMENT token found at index " + i + ": " + token.getText());
            }
        }

        // 使用过滤后的tokens重新创建token stream
        CommonTokenStream filteredTokenStream = new CommonTokenStream(lexer);
        filteredTokenStream.getTokens().clear();
        filteredTokenStream.getTokens().addAll(filteredTokens);

        FLDorisParser flparser = new FLDorisParser(filteredTokenStream);
        ParseTree tree = flparser.querySpecification();
        List<FilterCondition> conditions = new ArrayList<>();
        try {
            WhereConditionExtractor visitor = new WhereConditionExtractor(tokens);
            visitor.visit(tree);
            conditions = visitor.getConditions();
        } catch (Exception e) {
            throw new Exception("提取WHERE条件信息报错," + e.getMessage());
        }
        return conditions;
    }

}

class StmtRequestBody {
    public String stmt;
    public boolean sql_validate;
    public boolean sql_condition;
}

class SelectCommentExtractor extends FLDorisParserBaseVisitor<Void> {
    private Map<String, String> fieldDescriptions = new LinkedHashMap<>();
    private Set<String> comments = new HashSet<>();
    private CommonTokenStream tokenStream;

    public SelectCommentExtractor(CommonTokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    @Override
    public Void visitRegularQuerySpecification(FLDorisParser.RegularQuerySpecificationContext ctx) {
        // 确保访问selectClause
        if (ctx.selectClause() != null) {
            visitSelectClause(ctx.selectClause());
        }
        return null;
    }

    @Override
    public Void visitSelectClause(FLDorisParser.SelectClauseContext ctx) {
        FLDorisParser.SelectColumnClauseContext selectColumnClause = ctx.selectColumnClause();
        if (selectColumnClause != null) {
            visitSelectColumnClause(selectColumnClause);
        }
        return null;
    }

    @Override
    public Void visitSelectColumnClause(FLDorisParser.SelectColumnClauseContext ctx) {
        FLDorisParser.NamedExpressionSeqContext namedSeq = ctx.namedExpressionSeq();
        if (namedSeq != null) {
            visitNamedExpressionSeq(namedSeq);
        }
        return null;
    }

    @Override
    public Void visitNamedExpressionSeq(FLDorisParser.NamedExpressionSeqContext ctx) {
        for (int i = 0; i < ctx.namedExpression().size(); i++) {
            FLDorisParser.NamedExpressionContext namedExpr = ctx.namedExpression(i);
            visitNamedExpression(namedExpr);
        }
        // 处理 COMMA 后的 SIMPLE_COMMENT（如果存在）
        for (int i = 0; i < ctx.COMMA().size(); i++) {
            Token commaComment = ctx.SELECT_FIELD_COMMENT(i) != null ? ctx.SELECT_FIELD_COMMENT(i).getSymbol() : null;
            if (commaComment != null && !commaComment.getText().isEmpty()) {
                // 如果逗号后有注释，归属到前一个字段（特殊情况处理）
                String prevField = ctx.namedExpression(i).identifierOrText() != null
                    ? ctx.namedExpression(i).identifierOrText().getText()
                    : ctx.namedExpression(i).expression().getText();
                String description = commaComment.getText().substring(2).trim().toLowerCase();
                if (StringUtils.isBlank(description)) {
                    throw new RuntimeException("注释不能为空");
                }
                if (comments.contains(description)) {
                    throw new RuntimeException("注释重复了:" + description);
                }
                comments.add(description);
                fieldDescriptions.put(prevField.replace("`", "").toLowerCase(), description);
            }
        }
        return null;
    }

    @Override
    public Void visitNamedExpression(FLDorisParser.NamedExpressionContext ctx) {
        String fieldName;
        String description = null;

        // Determine field name
        if (ctx.identifierOrText() != null) {
            fieldName = ctx.identifierOrText().getText();
        } else {
            fieldName = ctx.expression().getText();
        }

        // Get the line number of the field
        int fieldLine = (ctx.identifierOrText() != null)
            ? ctx.identifierOrText().getStop().getLine()
            : ctx.expression().getStop().getLine();

        // Check for comment within the namedExpression
        if (ctx.SELECT_FIELD_COMMENT() != null) {
            Token commentToken = ctx.SELECT_FIELD_COMMENT().getSymbol();
            if (commentToken.getLine() == fieldLine) {
                description = commentToken.getText().substring(2).trim().toLowerCase();
            }
        }

        // Default description to fieldName if no comment is found yet
        if (description == null) {
            description = fieldName.toLowerCase();
        }

        // Validate description
        if (description.contains("'")) {
            throw new RuntimeException("注释不能包含单引号");
        }

        if (StringUtils.isBlank(description)) {
            throw new RuntimeException("注释不能为空");
        }

        if (comments.contains(description)) {
            throw new RuntimeException("注释重复了:" + description);
        }
        comments.add(description);
        fieldDescriptions.put(fieldName.replace("`", "").toLowerCase(), description);
        return null;
    }

    public Map<String, String> getFieldDescriptions() {
        return fieldDescriptions;
    }
}

enum ConditionType {
    WHERE,    // WHERE子句中的条件
    JOIN      // JOIN ON子句中的条件
}

class FilterCondition implements Serializable {
    private String column;           // 过滤列
    private String operator;         // 条件类型（=, >, <, IN, LIKE等）
    private List<String> valueList;  // 条件值列表，支持多值
    private String originalText;     // 原始文本，用于调试
    private boolean isNot;           // 是否是NOT条件
    private ConditionType type;      // 条件类型：WHERE条件或JOIN条件

    // 构造函数 - 支持单个值
    public FilterCondition(String column, String operator, String value, String originalText, boolean isNot, ConditionType type) {
        this.column = column;
        this.operator = operator;
        this.valueList = new ArrayList<>();
        if (value != null) {
            this.valueList.add(value);
        }
        this.originalText = originalText;
        this.isNot = isNot;
        this.type = type;
    }

    // 构造函数 - 支持多个值
    public FilterCondition(String column, String operator, List<String> valueList, String originalText, boolean isNot, ConditionType type) {
        this.column = column;
        this.operator = operator;
        this.valueList = valueList != null ? new ArrayList<>(valueList) : new ArrayList<>();
        this.originalText = originalText;
        this.isNot = isNot;
        this.type = type;
    }

    // 兼容旧代码的构造函数 - 默认为WHERE条件
    public FilterCondition(String column, String operator, String value, String originalText, boolean isNot) {
        this(column, operator, value, originalText, isNot, ConditionType.WHERE);
    }

    // 兼容旧代码的构造函数 - 默认为WHERE条件
    public FilterCondition(String column, String operator, List<String> valueList, String originalText, boolean isNot) {
        this(column, operator, valueList, originalText, isNot, ConditionType.WHERE);
    }


    // getter and setter, for json serialization
    public String getColumn() {
        return column;
    }

    public void setColumn(String column) {
        this.column = column;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public List<String> getValueList() {
        return valueList;
    }

    public void setValueList(List<String> valueList) {
        this.valueList = valueList;
    }

    public String getOriginalText() {
        return originalText;
    }

    public void setOriginalText(String originalText) {
        this.originalText = originalText;
    }

    public boolean isNot() {
        return isNot;
    }

    public void setNot(boolean not) {
        isNot = not;
    }

    public ConditionType getType() {
        return type;
    }

    public void setType(ConditionType type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return String.format("FilterCondition{type=%s, column='%s', operator='%s', valueList=%s, isNot=%s, originalText='%s'}",
            type, column, operator, valueList, isNot, originalText);
    }
}

class WhereConditionExtractor extends FLDorisParserBaseVisitor<Void> {
    private List<FilterCondition> conditions = new ArrayList<>();
    private CommonTokenStream tokenStream;
    private ConditionType currentConditionType = ConditionType.WHERE; // 当前处理的条件类型

    public WhereConditionExtractor(CommonTokenStream tokenStream) {
        this.tokenStream = tokenStream;
    }

    @Override
    public Void visitRegularQuerySpecification(FLDorisParser.RegularQuerySpecificationContext ctx) {
        // 确保访问whereClause
        if (ctx.whereClause() != null) {
            visitWhereClause(ctx.whereClause());
        }

        // 手动访问其他部分以避免重复访问whereClause
        if (ctx.selectClause() != null) {
            visit(ctx.selectClause());
        }
        if (ctx.fromClause() != null) {
            visit(ctx.fromClause());
        }
        if (ctx.aggClause() != null) {
            visit(ctx.aggClause());
        }
        if (ctx.havingClause() != null) {
            visit(ctx.havingClause());
        }
        if (ctx.queryOrganization() != null) {
            visit(ctx.queryOrganization());
        }

        return null;
    }

    // 添加对FROM子句中子查询的支持
    @Override
    public Void visitAliasedQuery(FLDorisParser.AliasedQueryContext ctx) {
        // 递归访问子查询
        if (ctx.query() != null) {
            visit(ctx.query());
        }
        return null;
    }

    // 添加对表达式中子查询的支持
    @Override
    public Void visitSubqueryExpression(FLDorisParser.SubqueryExpressionContext ctx) {
        // 递归访问子查询
        if (ctx.query() != null) {
            visit(ctx.query());
        }
        return null;
    }

    // 添加对JOIN条件的处理
    @Override
    public Void visitJoinRelation(FLDorisParser.JoinRelationContext ctx) {
        // 处理JOIN条件时设置上下文为JOIN
        if (ctx.joinCriteria() != null) {
            ConditionType savedType = currentConditionType;
            currentConditionType = ConditionType.JOIN;
            visit(ctx.joinCriteria());
            currentConditionType = savedType; // 恢复之前的上下文
        }

        // 继续处理其他部分（如右表）
        if (ctx.right != null) {
            visit(ctx.right);
        }

        return null;
    }

    @Override
    public Void visitWhereClause(FLDorisParser.WhereClauseContext ctx) {
        if (ctx.booleanExpression() != null) {
            visitBooleanExpression(ctx.booleanExpression());
        }
        return null;
    }

    public Void visitBooleanExpression(FLDorisParser.BooleanExpressionContext ctx) {
        return visit(ctx);
    }

    @Override
    public Void visitLogicalBinary(FLDorisParser.LogicalBinaryContext ctx) {
        // 处理 AND/OR 操作，递归访问左右子表达式
        visit(ctx.left);
        visit(ctx.right);
        return null;
    }

    @Override
    public Void visitPredicated(FLDorisParser.PredicatedContext ctx) {
        if (ctx.predicate() != null) {
            // 有谓词的情况，如 column IN (values) 或 column LIKE pattern
            visitPredicateWithColumn(ctx.valueExpression(), ctx.predicate());
        } else {
            // 没有谓词，可能是简单的值表达式，继续向下访问
            visit(ctx.valueExpression());
        }
        return null;
    }

    @Override
    public Void visitComparison(FLDorisParser.ComparisonContext ctx) {
        // 处理比较操作，如 column = value, column > value 等
        String leftColumn = extractColumnName(ctx.left);
        String rightValue = extractValue(ctx.right);
        String operator = ctx.comparisonOperator().getText();

        if (leftColumn != null && rightValue != null) {
            FilterCondition condition = new FilterCondition(
                leftColumn,
                operator,
                rightValue,
                ctx.getText(),
                false,
                currentConditionType
            );
            conditions.add(condition);
        }
        return null;
    }

    private void visitPredicateWithColumn(FLDorisParser.ValueExpressionContext valueExpr,
                                          FLDorisParser.PredicateContext predicate) {
        String column = extractColumnName(valueExpr);
        if (column == null) return;

        boolean isNot = predicate.NOT() != null;

        if (predicate.kind != null) {
            String operator = predicate.kind.getText().toUpperCase();

            switch (operator) {
                case "IN":
                    handleInPredicate(column, predicate, isNot);
                    break;
                case "LIKE":
                case "REGEXP":
                case "RLIKE":
                    String pattern = extractValue(predicate.pattern);
                    if (pattern != null) {
                        FilterCondition condition = new FilterCondition(
                            column,
                            operator,
                            pattern,
                            predicate.getText(),
                            isNot,
                            currentConditionType
                        );
                        conditions.add(condition);
                    }
                    break;
                case "BETWEEN":
                    String lowerValue = extractValue(predicate.lower);
                    String upperValue = extractValue(predicate.upper);
                    if (lowerValue != null && upperValue != null) {
                        List<String> betweenValues = new ArrayList<>();
                        betweenValues.add(lowerValue);
                        betweenValues.add(upperValue);
                        FilterCondition condition = new FilterCondition(
                            column,
                            "BETWEEN",
                            betweenValues,  // 使用多值构造函数
                            predicate.getText(),
                            isNot,
                            currentConditionType
                        );
                        conditions.add(condition);
                    }
                    break;
                case "NULL":
                    FilterCondition condition = new FilterCondition(
                        column,
                        "IS NULL",
                        "NULL",
                        predicate.getText(),
                        isNot,
                        currentConditionType
                    );
                    conditions.add(condition);
                    break;
            }
        }
    }

    private void handleInPredicate(String column, FLDorisParser.PredicateContext predicate, boolean isNot) {
        if (predicate.query() != null) {
            // IN (subquery) 的情况 - 子查询作为单个值处理
            FilterCondition condition = new FilterCondition(
                column,
                "IN",
                "(" + predicate.query().getText() + ")",
                predicate.getText(),
                isNot,
                currentConditionType
            );
            conditions.add(condition);

            // 递归访问IN子查询中的WHERE条件
            visit(predicate.query());
        } else if (predicate.expression() != null && !predicate.expression().isEmpty()) {
            // IN (value1, value2, ...) 的情况 - 使用多值构造函数
            List<String> values = new ArrayList<>();
            for (FLDorisParser.ExpressionContext expr : predicate.expression()) {
                String value = extractValue(expr);
                if (value != null) {
                    values.add(value);
                }
            }
            if (!values.isEmpty()) {
                FilterCondition condition = new FilterCondition(
                    column,
                    "IN",
                    values,  // 直接传递List<String>
                    predicate.getText(),
                    isNot,
                    currentConditionType
                );
                conditions.add(condition);
            }
        }
    }

    private String extractColumnName(FLDorisParser.ValueExpressionContext ctx) {
        return extractColumnName((ParseTree) ctx);
    }

    private String extractColumnName(FLDorisParser.ExpressionContext ctx) {
        return extractColumnName((ParseTree) ctx);
    }

    private String extractColumnName(ParseTree ctx) {
        if (ctx == null) return null;

        // 简单的列引用检测
        String text = ctx.getText();

        // 移除反引号
        if (text.startsWith("`") && text.endsWith("`")) {
            text = text.substring(1, text.length() - 1);
        }

        // 如果包含点号，取最后一部分作为列名
        if (text.contains(".")) {
            String[] parts = text.split("\\.");
            text = parts[parts.length - 1];
            if (text.startsWith("`") && text.endsWith("`")) {
                text = text.substring(1, text.length() - 1);
            }
        }

        // 简单验证是否看起来像列名（字母、数字、下划线）
        if (text.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            return text;
        }

        return null;
    }

    private String extractValue(FLDorisParser.ValueExpressionContext ctx) {
        return extractValue((ParseTree) ctx);
    }

    private String extractValue(FLDorisParser.ExpressionContext ctx) {
        return extractValue((ParseTree) ctx);
    }

    private String extractValue(ParseTree ctx) {
        if (ctx == null) return null;

        String text = ctx.getText();

        // 移除字符串字面量的引号
        if ((text.startsWith("'") && text.endsWith("'")) ||
            (text.startsWith("\"") && text.endsWith("\""))) {
            return text.substring(1, text.length() - 1);
        }

        return text;
    }

    public List<FilterCondition> getConditions() {
        return conditions;
    }
}
