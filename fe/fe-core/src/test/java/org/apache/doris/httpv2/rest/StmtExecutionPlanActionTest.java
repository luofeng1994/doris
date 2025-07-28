package org.apache.doris.httpv2.rest;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.doris.nereids.FLDorisLexer;
import org.apache.doris.nereids.FLDorisParser;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StmtExecutionPlanActionTest {
    @Test
    public void testGetFieldDescriptions() {
        String sql = "/**\n" +
            "* 描述：\n" +
            "* 创建人：系统管理员\n" +
            "* 创建时间：20250728 14:40:51\n" +
            "**/\n" +
            "select t_1.create_user as create_user -- 创建用户\n" +
            "  , t_1.cat_type as cat_type -- 物标签大类\n" +
            "  , t_1.group_id as group_id -- 物标签组ID\n" +
            "  , t_1.group_name as group_name -- 物标签组名\n" +
            "  , t_1.parent_id as parent_id -- 父级标签组ID\n" +
            "  , t_1.otag_cnt as otag_cnt -- 直属标签数\n" +
            "  , t_1.create_time as create_time -- 创建时间\n" +
            "  , t_1.update_user as update_user -- 更新用户\n" +
            "  , t_1.update_time as update_time -- 更新时间\n" +
            "  , t_1.system_flag as system_flag -- 是否系统内置\n" +
            "from t_1 -- 元数据物标签分类表";
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

}
