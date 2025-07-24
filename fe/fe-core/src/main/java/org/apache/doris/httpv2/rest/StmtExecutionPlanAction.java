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
import org.apache.doris.analysis.Analyzer;
import org.apache.doris.analysis.Expr;
import org.apache.doris.analysis.QueryStmt;
import org.apache.doris.analysis.SelectListItem;
import org.apache.doris.analysis.SelectStmt;
import org.apache.doris.analysis.SqlParser;
import org.apache.doris.analysis.SqlScanner;
import org.apache.doris.analysis.StatementBase;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.StringReader;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


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
        return validate ? validateSql(sql) : getSchema(sql);
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
                field.put("name", colLabels.get(i));
                field.put("type", resultExprs.get(i).getType().toSql());
                metaFields.add(field);
            }
            return ResponseEntityBuilder.ok(metaFields);
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
                return ResponseEntityBuilder.internalError("自定义SQL只支持单个查询语句");
            }
            stmt = statements.get(0);
            // 2 只允许执行select查询
            if (!(stmt instanceof SelectStmt)) {
                return ResponseEntityBuilder.internalError("自定义SQL只支持select语句");
            }
            // 3 禁止使用with xx as ( select * from xxx)
            SelectStmt queryStmt = (SelectStmt) stmt;
            if (queryStmt.getWithClause() != null) {
                return ResponseEntityBuilder.internalError("自定义SQL不支持with as cte语法");
            }
            // 4 select字段 获取 alias 判断是否空
            for (SelectListItem item : queryStmt.getSelectList().getItems()) {
                String alias = item.getAlias();
                if (StringUtils.isBlank(alias)) {
                    return ResponseEntityBuilder.internalError("自定义SQL中Select字段别名不能为空");
                }
                // 检查是否包含中文（循环遍历字符）
                boolean hasChinese = false;
                for (char c : alias.toCharArray()) {
                    if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                        hasChinese = true;
                        break;
                    }
                }
                if (hasChinese || !alias.matches(COLUMN_NAME_REGEX)) {
                    return ResponseEntityBuilder.internalError("自定义SQL中Select字段别名不能包含中文及特殊符号");
                }
            }
            // 5 获取select字段后注释信息
            Map<String, String> descriptions = getFieldDescriptions(sql);

            Analyzer analyzer = new Analyzer(Env.getCurrentEnv(), ConnectContext.get());
            queryStmt.analyze(analyzer);
            ArrayList<String> colLabels = queryStmt.getColLabels();
            ArrayList<Expr> resultExprs = queryStmt.getResultExprs();
            List<Map<String, String>> metaFields = Lists.newArrayList();
            for (int i = 0; i < colLabels.size(); i++) {
                Map<String, String> field = Maps.newHashMap();
                String name = colLabels.get(i);
                if (!descriptions.containsKey(name)) {
                    throw new RuntimeException("请按照规范设置最外层所有查询字段的注释信息");
                }
                field.put("name", name);
                field.put("comment", descriptions.get(name));
                field.put("type", resultExprs.get(i).getType().toSql());
                metaFields.add(field);
            }
            return ResponseEntityBuilder.ok(metaFields);
        } catch (AnalysisException e) {
            return ResponseEntityBuilder.internalError(parser.getErrorMsg(sql));
        } catch (Exception e) {
            return ResponseEntityBuilder.internalError(e.getMessage());
        }
    }

    public Map<String, String> getFieldDescriptions(String sql) {
        FLDorisLexer lexer = new FLDorisLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FLDorisParser flparser = new FLDorisParser(tokens);
        ParseTree tree = flparser.selectColumnClause();
        Map<String, String> descriptions = new HashMap<>();
        try {
            SelectCommentExtractor visitor = new SelectCommentExtractor();
            visitor.visit(tree);
            descriptions = visitor.getFieldDescriptions();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return descriptions;
    }
}

class StmtRequestBody {
    public String stmt;
    public boolean sql_validate;
}

class SelectCommentExtractor extends FLDorisParserBaseVisitor<Void> {
    private Map<String, String> fieldDescriptions = new LinkedHashMap<>();

    @Override
    public Void visitSelectColumnClause(FLDorisParser.SelectColumnClauseContext ctx) {
        FLDorisParser.NamedExpressionSeqContext namedSeq = ctx.namedExpressionSeq();
        if (namedSeq != null) {
            visitNamedExpressionSeq(namedSeq);
        }
        return null;
    }

    @Override
    public Void visitNamedExpression(FLDorisParser.NamedExpressionContext ctx) {
        String fieldName;
        String description = null;

        // 获取字段名
        if (ctx.identifierOrText() != null) {
            fieldName = ctx.identifierOrText().getText();
        } else {
            fieldName = ctx.expression().getText();
        }

        // 获取最后一个非注释Token的行号
        int lastExprLine = -1;
        // 处理AS或别名的行号
        if (ctx.AS() != null) {
            lastExprLine = ctx.AS().getSymbol().getLine();
        } else if (ctx.identifierOrText() != null) {
            lastExprLine = ctx.identifierOrText().getStop().getLine();
        } else {
            // 获取表达式结束行号
            FLDorisParser.ExpressionContext expr = ctx.expression();
            if (expr != null && expr.getStop() != null) {
                lastExprLine = expr.getStop().getLine();
            }
        }

        // 处理字段注释
        Token commentToken = ctx.SELECT_FIELD_COMMENT() != null ? ctx.SELECT_FIELD_COMMENT().getSymbol() : null;
        if (commentToken != null) {
            int commentLine = commentToken.getLine();
            // 仅当注释与字段在同一行时处理
            if (commentLine == lastExprLine) {
                description = commentToken.getText().substring(2).trim();
            }
        }

        // 默认描述为字段名或空（根据需求调整）
        if (description == null) {
            description = fieldName;
        }

        if (description.contains("'")) {
            throw new RuntimeException("注释不能包含单引号");
        }
        fieldDescriptions.put(fieldName, description);
        return null;
    }

    @Override
    public Void visitNamedExpressionSeq(FLDorisParser.NamedExpressionSeqContext ctx) {
        for (FLDorisParser.NamedExpressionContext namedExpr : ctx.namedExpression()) {
            visitNamedExpression(namedExpr);
        }
        return null;
    }

    public Map<String, String> getFieldDescriptions() {
        return fieldDescriptions;
    }
}


