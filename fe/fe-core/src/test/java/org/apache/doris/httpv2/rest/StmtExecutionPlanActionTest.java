package org.apache.doris.httpv2.rest;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.apache.doris.nereids.FLDorisLexer;
import org.apache.doris.nereids.FLDorisParser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

class StmtExecutionPlanActionTest {
    private StmtExecutionPlanAction action = new StmtExecutionPlanAction();

    public static void main(String[] args) {
        StmtExecutionPlanActionTest test = new StmtExecutionPlanActionTest();
        // test.testGetFieldDescriptions();
        test.testJoinWhereCondition();
        // test.testBasicWhereCondition();
        // test.testNestedQueryWhereCondition();
    }

    public void testGetFieldDescriptions() {
        String sql = "/**\n" +
            "* 描述：\n" +
            "* 创建人：市公安局-02\n" +
            "* 创建时间：20250729 11:49:38\n" +
            "**/\n" +
            "select t_4.FID_MSISDN as FID_MSISDN -- 手机号\n" +
            "  , t_4.IMEI as IMEI -- 字段IMEI\n" +
            "  , t_4.IMSI as IMSI -- 字段IMSI\n" +
            "  , t_4.MCC as MCC -- 字段MCC\n" +
            "  , t_4.MNC as MNC -- 字段MNC\n" +
            "  , t_4.LAC as LAC -- 字段LAC\n" +
            "  , t_4.CI as CI -- 字段CI\n" +
            "  , t_4.FID_FCGI as FID_FCGI -- 字段FID_FCGI\n" +
            "  , t_4.USERNAME as USERNAME -- 字段USERNAME\n" +
            "from t_4 -- 表名\n" +
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
        System.out.println(descriptions);
    }

    public void testJoinWhereCondition() {
        System.out.println("=== JOIN查询WHERE条件提取测试 ===");

        // 测试案例1：基本INNER JOIN
        String sql1 = "SELECT t1.id, t2.name FROM users t1 JOIN profiles t2 ON t1.id = t2.user_id WHERE t1.age > 18 AND t2.status = 'active'";
        testSingleJoinSql("基本INNER JOIN", sql1, 3); // JOIN条件 + 2个WHERE条件

        // 测试案例2：LEFT JOIN
        String sql2 = "SELECT u.name, p.phone FROM users u LEFT JOIN phones p ON u.id = p.user_id WHERE u.created_date > '2023-01-01'";
        testSingleJoinSql("LEFT JOIN", sql2, 2); // JOIN条件 + 1个WHERE条件

        // 测试案例3：多表JOIN
        String sql3 = "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id JOIN products p ON o.product_id = p.id WHERE o.status = 'shipped' AND c.region = 'US' AND p.price > 100";
        testSingleJoinSql("多表JOIN", sql3, 5); // 2个JOIN条件 + 3个WHERE条件

        // 测试案例4：RIGHT JOIN with复杂WHERE条件
        String sql4 = "SELECT * FROM employees e RIGHT JOIN departments d ON e.dept_id = d.id WHERE d.budget > 50000 AND (e.salary IS NULL OR e.salary > 30000)";
        testSingleJoinSql("RIGHT JOIN复杂WHERE", sql4, 4); // JOIN条件 + budget条件 + IS NULL条件 + salary条件

        // 测试案例5：JOIN与子查询结合
        String sql5 = "SELECT * FROM users u JOIN (SELECT dept_id, COUNT(*) as emp_count FROM employees WHERE active = 1 GROUP BY dept_id) e ON u.dept_id = e.dept_id WHERE u.age BETWEEN 25 AND 55";
        testSingleJoinSql("JOIN与子查询", sql5, 3); // JOIN条件 + 子查询WHERE + 外层WHERE

        // 测试案例6：FULL OUTER JOIN（如果支持）
        String sql6 = "SELECT * FROM table1 t1 FULL OUTER JOIN table2 t2 ON t1.key = t2.key WHERE t1.value > 100 OR t2.value < 50";
        testSingleJoinSql("FULL OUTER JOIN", sql6, 3); // JOIN条件 + 2个WHERE条件

        // 测试案例7：CROSS JOIN
        String sql7 = "SELECT * FROM categories c CROSS JOIN products p WHERE c.active = 1 AND p.stock > 0 AND p.price BETWEEN 10 AND 1000";
        testSingleJoinSql("CROSS JOIN", sql7, 3); // 3个WHERE条件（CROSS JOIN无ON条件）

        // 测试案例8：嵌套JOIN查询
        String sql8 = "SELECT * FROM (SELECT u.*, d.name as dept_name FROM users u JOIN departments d ON u.dept_id = d.id WHERE u.active = 1) t JOIN roles r ON t.role_id = r.id WHERE r.permission_level > 3";
        testSingleJoinSql("嵌套JOIN", sql8, 4); // 内层JOIN条件 + 外层JOIN条件 + 内层WHERE + 外层WHERE

        System.out.println("\n=== 小写关键词JOIN测试 ===");

        // 测试案例9：小写基本JOIN
        String sql9 = "select t1.id, t2.name from users t1 join profiles t2 on t1.id = t2.user_id where t1.age > 18 and t2.status = 'active'";
        testSingleJoinSql("小写基本JOIN", sql9, 3); // JOIN条件 + 2个WHERE条件

        // 测试案例10：小写LEFT JOIN with复杂条件
        String sql10 = "select u.name, p.phone from users u left join phones p on u.id = p.user_id where u.created_date > '2023-01-01' and p.type in ('mobile', 'work')";
        testSingleJoinSql("小写LEFT JOIN", sql10, 3); // JOIN条件 + 2个WHERE条件

        // 测试案例11：小写RIGHT JOIN
        String sql11 = "select * from employees e right join departments d on e.dept_id = d.id where d.budget > 50000 or e.salary is null";
        testSingleJoinSql("小写RIGHT JOIN", sql11, 3); // JOIN条件 + 2个WHERE条件

        // 测试案例12：小写多表JOIN
        String sql12 = "select * from orders o inner join customers c on o.customer_id = c.id inner join products p on o.product_id = p.id where o.status = 'shipped' and c.region = 'US'";
        testSingleJoinSql("小写多表JOIN", sql12, 4); // 2个JOIN条件 + 2个WHERE条件

        // 测试案例13：小写CROSS JOIN
        String sql13 = "select * from categories c cross join products p where c.active = 1 and p.stock > 0";
        testSingleJoinSql("小写CROSS JOIN", sql13, 2); // 2个WHERE条件（CROSS JOIN无ON条件）

        // 测试案例14：小写混合大小写
        String sql14 = "Select t1.name From users t1 Left Join profiles t2 On t1.id = t2.user_id Where t1.status = 'active' And t2.verified = true";
        testSingleJoinSql("混合大小写JOIN", sql14, 3); // JOIN条件 + 2个WHERE条件

        // 测试案例15：小写复杂条件
        String sql15 = "select * from users u join orders o on u.id = o.user_id where u.age between 18 and 65 and o.amount > 100 and o.status not in ('cancelled', 'refunded')";
        testSingleJoinSql("小写复杂条件", sql15, 4); // JOIN条件 + BETWEEN条件 + 比较条件 + NOT IN条件

        System.out.println("=== JOIN查询测试完成 ===");
    }

    private void testSingleJoinSql(String testName, String sql, int expectedCount) {
        System.out.println("\n【" + testName + "】");
        System.out.println("SQL: " + sql);
        try {
            List<FilterCondition> conditions = action.getWhereConditions(sql);
            System.out.println("预期条件数量: " + expectedCount + ", 实际提取: " + conditions.size());

            // 分类显示条件
            int joinConditions = 0;
            int whereConditions = 0;

            for (FilterCondition condition : conditions) {
                // 使用新的条件类型字段
                if (condition.getType() == ConditionType.JOIN) {
                    joinConditions++;
                } else {
                    whereConditions++;
                }
            }

            System.out.println("JOIN条件: " + joinConditions + ", WHERE条件: " + whereConditions);

            if (conditions.size() == expectedCount) {
                System.out.println("✅ 测试通过");
            } else {
                System.out.println("❌ 测试失败 - 条件数量不匹配");
            }

            for (int i = 0; i < conditions.size(); i++) {
                FilterCondition condition = conditions.get(i);
                String type = condition.getType() == ConditionType.JOIN ? "[JOIN]" : "[WHERE]";
                System.out.println("  " + type + " 条件" + (i + 1) + ": " + condition.toString());
            }
        } catch (Exception e) {
            System.out.println("❌ 解析失败: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("---");
    }

    public void testBasicWhereCondition() {
        System.out.println("=== 基础WHERE条件提取测试 ===\n");

        System.out.println("--- 基础条件测试 ---");

        // 测试案例1：简单等值条件
        testSingleBasicSql("简单等值条件", "SELECT * FROM table1 WHERE id = 123", 1);

        // 测试案例2：多条件AND
        testSingleBasicSql("多条件AND", "SELECT * FROM table1 WHERE id = 123 AND name LIKE 'test%' AND age > 18", 3);

        // 测试案例3：NOT IN条件
        testSingleBasicSql("NOT IN条件", "SELECT * FROM table1 WHERE id NOT IN (1, 2, 3)", 1);

        // 测试案例4：BETWEEN条件
        testSingleBasicSql("BETWEEN条件", "SELECT * FROM table1 WHERE age BETWEEN 18 AND 65", 1);

        // 测试案例5：IS NULL条件
        testSingleBasicSql("IS NULL条件", "SELECT * FROM table1 WHERE description IS NULL", 1);

        System.out.println("\n--- 小写关键词测试 ---");

        // 测试案例6：小写基础条件
        testSingleBasicSql("小写基础条件", "select * from table1 where id = 123", 1);

        // 测试案例7：小写多条件
        testSingleBasicSql("小写多条件", "select * from table1 where id = 123 and name like 'test%' and age > 18", 3);

        // 测试案例8：小写NOT IN
        testSingleBasicSql("小写NOT IN", "select * from table1 where id not in (1, 2, 3)", 1);

        // 测试案例9：小写BETWEEN
        testSingleBasicSql("小写BETWEEN", "select * from table1 where age between 18 and 65", 1);

        // 测试案例10：小写IS NULL
        testSingleBasicSql("小写IS NULL", "select * from table1 where description is null", 1);

        System.out.println("\n--- 嵌套查询测试 ---");

        // 测试案例11：简单嵌套查询
        testSingleBasicSql("简单嵌套查询", "SELECT * FROM (SELECT * FROM table2 WHERE id = 123) table1 WHERE status IN ('active', 'pending')", 2);

        // 测试案例12：小写嵌套查询
        testSingleBasicSql("小写嵌套查询", "select * from (select * from table2 where id = 123) table1 where status in ('active', 'pending')", 2);

        System.out.println("\n--- 复杂SQL测试 ---");

        // 测试案例13：包含注释的复杂嵌套
        String complexSql = "select * from (select t_4.FID_MSISDN as FID_MSISDN, t_4.IMEI as IMEI " +
            "from t_4 where FID_MSISDN = '123456' and IMEI in ('1231231', '12312312')) t_5 " +
            "where FID_MSISDN is not null";
        testSingleBasicSql("复杂嵌套查询", complexSql, 3);

        // 测试案例14：JOIN查询
        testSingleBasicSql("JOIN查询", "select t1.MSISDN from t1 join t2 on t1.MSISDN = t2.USER where t1.MSISDN = '13718656802'", 2);

        System.out.println("\n=== 基础WHERE条件测试完成 ===");
    }

    public void testNestedQueryWhereCondition() {
        System.out.println("=== 嵌套查询WHERE条件提取测试 ===");

        // 测试案例1：FROM子句中的简单子查询
        String sql1 = "SELECT * FROM (SELECT * FROM users WHERE age > 18) t WHERE status = 'active'";
        testSingleNestedSql("FROM子句简单子查询", sql1, 2);

        // 测试案例2：多层嵌套子查询
        String sql2 = "SELECT * FROM (SELECT * FROM (SELECT * FROM orders WHERE amount > 100) o1 WHERE created_date > '2023-01-01') o2 WHERE status IN ('pending', 'processing')";
        testSingleNestedSql("多层嵌套子查询", sql2, 3);

        // 测试案例3：IN子查询
        String sql3 = "SELECT * FROM products WHERE category_id IN (SELECT id FROM categories WHERE name = 'electronics') AND price > 500";
        testSingleNestedSql("IN子查询", sql3, 3);

        // 测试案例4：EXISTS子查询
        String sql4 = "SELECT * FROM customers c WHERE EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'completed') AND c.region = 'US'";
        testSingleNestedSql("EXISTS子查询", sql4, 3);

        // 测试案例5：SELECT字段中的子查询
        String sql5 = "SELECT id, name, (SELECT COUNT(*) FROM orders WHERE customer_id = c.id AND status = 'active') as active_orders FROM customers c WHERE c.created_date > '2023-01-01'";
        testSingleNestedSql("SELECT字段子查询", sql5, 3);

        // 测试案例6：复杂混合嵌套
        String sql6 = "SELECT * FROM (SELECT u.*, (SELECT COUNT(*) FROM posts WHERE user_id = u.id AND status = 'published') as post_count FROM users u WHERE u.age BETWEEN 20 AND 60) t WHERE t.post_count > 5 AND t.region IN (SELECT region FROM active_regions WHERE population > 1000000)";
        testSingleNestedSql("复杂混合嵌套", sql6, 6);

        // 测试案例7：NOT EXISTS子查询
        String sql7 = "SELECT * FROM customers c WHERE NOT EXISTS (SELECT 1 FROM orders o WHERE o.customer_id = c.id AND o.status = 'cancelled') AND c.active = 1";
        testSingleNestedSql("NOT EXISTS子查询", sql7, 3);

        // 测试案例8：UNION中的嵌套查询
        String sql8 = "SELECT * FROM (SELECT id, name FROM users WHERE age > 25) t1 UNION SELECT * FROM (SELECT id, name FROM admins WHERE level > 3) t2";
        testSingleNestedSql("UNION嵌套查询", sql8, 2);

        System.out.println("=== 嵌套查询测试完成 ===");
    }

    private void testSingleNestedSql(String testName, String sql, int expectedCount) {
        System.out.println("\n【" + testName + "】");
        System.out.println("SQL: " + sql);
        try {
            List<FilterCondition> conditions = action.getWhereConditions(sql);
            System.out.println("预期条件数量: " + expectedCount + ", 实际提取: " + conditions.size());

            if (conditions.size() == expectedCount) {
                System.out.println("✅ 测试通过");
            } else {
                System.out.println("❌ 测试失败 - 条件数量不匹配");
            }

            for (int i = 0; i < conditions.size(); i++) {
                System.out.println("  条件" + (i + 1) + ": " + conditions.get(i).toString());
            }
        } catch (Exception e) {
            System.out.println("❌ 解析失败: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("---");
    }

    private void testSingleBasicSql(String testName, String sql, int expectedCount) {
        System.out.println("\n【" + testName + "】");
        System.out.println("SQL: " + sql);
        try {
            List<FilterCondition> conditions = action.getWhereConditions(sql);
            System.out.println("预期条件数量: " + expectedCount + ", 实际提取: " + conditions.size());

            // 统计条件类型
            long joinCount = conditions.stream().filter(c -> c.getType() == ConditionType.JOIN).count();
            long whereCount = conditions.stream().filter(c -> c.getType() == ConditionType.WHERE).count();

            if (joinCount > 0) {
                System.out.println("条件分布: JOIN条件 " + joinCount + " 个, WHERE条件 " + whereCount + " 个");
            }

            if (conditions.size() == expectedCount) {
                System.out.println("✅ 测试通过");
            } else {
                System.out.println("❌ 测试失败 - 条件数量不匹配");
            }

            // 按类型分组显示条件
            for (int i = 0; i < conditions.size(); i++) {
                FilterCondition condition = conditions.get(i);
                String typeLabel = condition.getType() == ConditionType.JOIN ? "[JOIN]" : "[WHERE]";
                String conditionSummary = String.format("%s %s %s %s",
                    condition.getColumn(),
                    condition.getOperator(),
                    condition.getValueList().size() == 1 ? condition.getValueList().get(0) : condition.getValueList(),
                    condition.isNot() ? "(NOT)" : "");
                System.out.println("  " + typeLabel + " 条件" + (i + 1) + ": " + conditionSummary);
            }
        } catch (Exception e) {
            System.out.println("❌ 解析失败: " + e.getMessage());
            // e.printStackTrace(); // 注释掉详细堆栈，让输出更简洁
        }
        System.out.println();
    }
}
