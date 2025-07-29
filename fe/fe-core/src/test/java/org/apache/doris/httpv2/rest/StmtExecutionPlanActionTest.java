package org.apache.doris.httpv2.rest;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.nereids.FLDorisLexer;
import org.apache.doris.nereids.FLDorisParser;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StmtExecutionPlanActionTest {
    @Test
    public void testGetFieldDescriptions() {
        String sql = "/**\n" +
            "* 描述：\n" +
            "* 创建人：市公安局-02\n" +
            "* 创建时间：20250729 11:49:38\n" +
            "**/\n" +
            "select t_4.FID_MSISDN as FID_MSISDN -- fid_msisdn\n" +
            "  , t_4.IMEI as IMEI -- imei\n" +
            "  , t_4.IMSI as IMSI -- imsi\n" +
            "  , t_4.MCC as MCC -- mcc\n" +
            "  , t_4.MNC as MNC -- mnc\n" +
            "  , t_4.LAC as LAC -- lac\n" +
            "  , t_4.CI as CI -- ci\n" +
            "  , t_4.FID_FCGI as FID_FCGI -- fid_fcgi\n" +
            "  , t_4.USERNAME as USERNAME -- username\n" +
            "from t_4 -- test_approval_Sheet12\n" +
            "where FID_MSISDN = '123456'";
        System.out.println(sql);
        FLDorisLexer lexer = new FLDorisLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        FLDorisParser flparser = new FLDorisParser(tokens);
        ParseTree tree = flparser.selectColumnClause();
        Map<String, String> descriptions = new HashMap<>();
        try {
            SelectCommentExtractor visitor = new SelectCommentExtractor(tokens);
            visitor.visit(tree);
            descriptions = visitor.getFieldDescriptions();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        System.out.println( descriptions);
    }

    @Test
    public void testBasicWhereCondition() {
        String sql1 = "SELECT * FROM table1 WHERE id = 123";
        String sql2 = "SELECT * FROM (SELECT * FROM table2 WHERE id = 123) table1 WHERE status IN ('active', 'pending')";
        String sql3 = "SELECT * FROM table1 WHERE id = 123 AND name LIKE 'test%' AND age > 18";
        String sql4 = "SELECT * FROM table1 WHERE id NOT IN (1, 2, 3)";
        String sql5 = "SELECT * FROM table1 WHERE age BETWEEN 18 AND 65";
        String sql6 = "SELECT * FROM table1 WHERE description IS NULL";
        String sql7 = "select * from table1 where id = 123";
        String sql8 = "select * from (select * from table2 where id = 123) table1 where status in ('active', 'pending')";
        String sql9 = "select * from table1 where id = 123 and name like 'test%' and age > 18";
        String sql10 = "select * from table1 where id not in (1, 2, 3)";
        String sql11 = "select * from table1 where age between 18 and 65";
        String sql12 = "select * from table1 where description is null";
        String sql13 = "/**\n" +
            "* 描述：\n" +
            "* 创建人：市公安局-02\n" +
            "* 创建时间：20250729 18:39:52\n" +
            "**/\n" +
            "select * from (select t_4.FID_MSISDN as FID_MSISDN -- fid_msisdn\n" +
            "  , t_4.IMEI as IMEI -- imei\n" +
            "  , t_4.IMSI as IMSI -- imsi\n" +
            "  , t_4.MCC as MCC -- mcc\n" +
            "  , t_4.MNC as MNC -- mnc\n" +
            "  , t_4.LAC as LAC -- lac\n" +
            "  , t_4.CI as CI -- ci\n" +
            "  , t_4.FID_FCGI as FID_FCGI -- fid_fcgi\n" +
            "  , t_4.USERNAME as USERNAME -- username\n" +
            "from t_4 -- test_approval_Sheet12\n" +
            "where FID_MSISDN = '123456' and IMEI in ('1231231', '12312312')\n" +
            ") t_5 where FID_MSISDN is not null";
//        String sql13 = "/**\n" +
//            "* 描述：\n" +
//            "* 创建时间：20250729 11:49:38\n" +
//            "**/\n" +
//            "select t_4.FID_MSISDN as FID_MSISDN -- fid_msisdn\n" +
//            "  , t_4.IMEI as IMEI -- imei\n" +
//            "  , t_4.IMSI as IMSI -- imsi\n" +
//            "  , t_4.MCC as MCC -- mcc\n" +
//            "  , t_4.MNC as MNC -- mnc\n" +
//            "  , t_4.LAC as LAC -- lac\n" +
//            "  , t_4.CI as CI -- ci\n" +
//            "  , t_4.FID_FCGI as FID_FCGI -- fid_fcgi\n" +
//            "  , t_4.USERNAME as USERNAME -- username\n" +
//            "from t_4 \n" +
//            "where FID_MSISDN = '123456'";
        List<String> sqlList = new ArrayList<>();
        sqlList.add(sql1);
        sqlList.add(sql2);
        sqlList.add(sql3);
        sqlList.add(sql4);
        sqlList.add(sql5);
        sqlList.add(sql6);
        sqlList.add(sql7);
        sqlList.add(sql8);
        sqlList.add(sql9);
        sqlList.add(sql10);
        sqlList.add(sql11);
        sqlList.add(sql12);
        sqlList.add(sql13);
        for (int i = 0; i < sqlList.size(); i++) {
            String sql = sqlList.get(i);
            System.out.println("Testing SQL" + (i+1) + ":");
            System.out.println(sql);
            try {
                List<FilterCondition> conditions = getWhereConditions(sql);
                System.out.println("条件数量: " + conditions.size());
                for (FilterCondition condition : conditions) {
                    System.out.println("  " + condition.toString());
                }
            } catch (Exception e) {
                System.out.println("解析失败: " + e.getMessage());
                e.printStackTrace();
            }
            System.out.println("---");
        }
    }


    public List<FilterCondition> getWhereConditions(String sql) throws AnalysisException {
        FLDorisLexer lexer = new FLDorisLexer(CharStreams.fromString(sql));
        CommonTokenStream tokens = new CommonTokenStream(lexer);

        // 过滤掉SELECT_FIELD_COMMENT token，避免影响WHERE条件解析
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
            WhereConditionExtractor visitor = new WhereConditionExtractor(filteredTokenStream);
            visitor.visit(tree);
            conditions = visitor.getConditions();
        } catch (Exception e) {
            throw new AnalysisException("提取WHERE条件信息报错," + e.getMessage());
        }
        return conditions;
    }

}
