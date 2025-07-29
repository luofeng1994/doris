# abc.jar
/bin/rm -rf abc
/bin/rm abc.jar
mkdir -p abc/antlr4/
mkdir -p abc/antlr4/org/apache/doris/nereids/
mkdir -p abc/org/apache/doris/nereids/
mkdir -p abc/antlr4/
mkdir -p abc/antlr4/org/apache/doris/nereids/
cp fe-core/target/classes/antlr4/FLD* abc/antlr4
cp fe-core/target/classes/antlr4/org/apache/doris/nereids/FLD* abc/antlr4/org/apache/doris/nereids
cp fe-core/target/classes/org/apache/doris/nereids/FLD* abc/org/apache/doris/nereids
cp fe-core/target/generated-sources/antlr4/FLD* abc/antlr4
cp fe-core/target/generated-sources/antlr4/org/apache/doris/nereids/FLD* abc/antlr4/org/apache/doris/nereids
jar cf abc.jar -C abc .

# doris-fe-extends.jar
mkdir -p doris-fe-extends/org/apache/doris/httpv2/rest/
cp fe-core/target/classes/org/apache/doris/httpv2/rest/StmtExecutionPlanAction* doris-fe-extends/org/apache/doris/httpv2/rest
cp fe-core/target/classes/org/apache/doris/httpv2/rest/StmtRequestBody.class doris-fe-extends/org/apache/doris/httpv2/rest
cp fe-core/target/classes/org/apache/doris/httpv2/rest/SelectCommentExtractor.class doris-fe-extends/org/apache/doris/httpv2/rest
cp fe-core/target/classes/org/apache/doris/httpv2/rest/FilterCondition.class doris-fe-extends/org/apache/doris/httpv2/rest
cp fe-core/target/classes/org/apache/doris/httpv2/rest/WhereConditionExtractor.class doris-fe-extends/org/apache/doris/httpv2/rest
jar cf doris-fe-extends.jar -C doris-fe-extends .
