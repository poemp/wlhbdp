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

package org.apache.doris.nereids.rules.analysis;

import org.apache.doris.common.NereidsException;
import org.apache.doris.nereids.CTEContext;
import org.apache.doris.nereids.NereidsPlanner;
import org.apache.doris.nereids.StatementContext;
import org.apache.doris.nereids.datasets.ssb.SSBUtils;
import org.apache.doris.nereids.exceptions.AnalysisException;
import org.apache.doris.nereids.glue.translator.PhysicalPlanTranslator;
import org.apache.doris.nereids.glue.translator.PlanTranslatorContext;
import org.apache.doris.nereids.parser.NereidsParser;
import org.apache.doris.nereids.properties.PhysicalProperties;
import org.apache.doris.nereids.rules.Rule;
import org.apache.doris.nereids.rules.RuleSet;
import org.apache.doris.nereids.rules.implementation.AggregateStrategies;
import org.apache.doris.nereids.rules.rewrite.InApplyToJoin;
import org.apache.doris.nereids.rules.rewrite.PullUpProjectUnderApply;
import org.apache.doris.nereids.rules.rewrite.UnCorrelatedApplyFilter;
import org.apache.doris.nereids.trees.expressions.StatementScopeIdGenerator;
import org.apache.doris.nereids.trees.plans.physical.PhysicalPlan;
import org.apache.doris.nereids.util.MemoPatternMatchSupported;
import org.apache.doris.nereids.util.MemoTestUtils;
import org.apache.doris.nereids.util.PlanChecker;
import org.apache.doris.utframe.TestWithFeService;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class RegisterCTETest extends TestWithFeService implements MemoPatternMatchSupported {

    private final NereidsParser parser = new NereidsParser();

    private final String sql1 = "WITH cte1 AS (SELECT s_suppkey FROM supplier WHERE s_suppkey < 5), "
            + "cte2 AS (SELECT s_suppkey FROM cte1 WHERE s_suppkey < 3)"
            + "SELECT * FROM cte1, cte2";

    private final String sql2 = "WITH cte1 (skey) AS (SELECT s_suppkey, s_nation FROM supplier WHERE s_suppkey < 5), "
            + "cte2 (sk2) AS (SELECT skey FROM cte1 WHERE skey < 3)"
            + "SELECT * FROM cte1, cte2";

    private final String sql3 = "WITH cte1 AS (SELECT * FROM supplier), "
            + "cte2 AS (SELECT * FROM supplier WHERE s_region in (\"ASIA\", \"AFRICA\"))"
            + "SELECT s_region, count(*) FROM cte1 GROUP BY s_region HAVING s_region in (SELECT s_region FROM cte2)";

    private final String sql4 = "WITH cte1 AS (SELECT s_suppkey AS sk FROM supplier WHERE s_suppkey < 5), "
            + "cte2 AS (SELECT sk FROM cte1 WHERE sk < 3)"
            + "SELECT * FROM cte1 JOIN cte2 ON cte1.sk = cte2.sk";

    private final String sql5 = "WITH V1 AS (SELECT s_suppkey FROM supplier), "
            + "V2 AS (SELECT s_suppkey FROM V1)"
            + "SELECT * FROM V2";

    private final String sql6 = "WITH cte1 AS (SELECT s_suppkey FROM supplier)"
            + "SELECT * FROM cte1 AS t1, cte1 AS t2";

    private final List<String> testSql = ImmutableList.of(
            sql1, sql2, sql3, sql4, sql5, sql6
    );

    @Override
    protected void runBeforeAll() throws Exception {
        createDatabase("test");
        useDatabase("test");
        SSBUtils.createTables(this);
        createView("CREATE VIEW V1 AS SELECT * FROM part");
        createView("CREATE VIEW V2 AS SELECT * FROM part");
    }

    @Override
    protected void runBeforeEach() throws Exception {
        StatementScopeIdGenerator.clear();
    }

    private CTEContext getCTEContextAfterRegisterCTE(String sql) {
        return PlanChecker.from(connectContext)
                .analyze(sql)
                .getCascadesContext()
                .getCteContext();
    }

    /* ********************************************************************************************
     * Test CTE
     * ******************************************************************************************** */

    @Test
    public void testTranslateCase() throws Exception {
        new MockUp<RuleSet>() {
            @Mock
            public List<Rule> getExplorationRules() {
                return Lists.newArrayList(new AggregateStrategies().buildRules());
            }
        };

        for (String sql : testSql) {
            StatementScopeIdGenerator.clear();
            StatementContext statementContext = MemoTestUtils.createStatementContext(connectContext, sql);
            PhysicalPlan plan = new NereidsPlanner(statementContext).plan(
                    parser.parseSingle(sql),
                    PhysicalProperties.ANY
            );
            // Just to check whether translate will throw exception
            new PhysicalPlanTranslator(new PlanTranslatorContext()).translatePlan(plan);
        }
    }

    @Test
    public void testCTERegister() {
        CTEContext cteContext = getCTEContextAfterRegisterCTE(sql1);

        Assertions.assertTrue(cteContext.containsCTE("cte1")
                && cteContext.containsCTE("cte2"));
        // LogicalPlan cte2parsedPlan = cteContext.getParsedCtePlan("cte2").get();
        // PlanChecker.from(connectContext, cte2parsedPlan)
        //         .matchesFromRoot(
        //             logicalSubQueryAlias(
        //                 logicalProject(
        //                     logicalFilter(
        //                         logicalCheckPolicy(
        //                             unboundRelation()
        //                         )
        //                     )
        //                 )
        //             )
        //         );
    }

    @Test
    public void testCTERegisterWithColumnAlias() {
        CTEContext cteContext = getCTEContextAfterRegisterCTE(sql2);

        Assertions.assertTrue(cteContext.containsCTE("cte1")
                && cteContext.containsCTE("cte2"));

        // check analyzed plan
        // LogicalPlan cte1AnalyzedPlan = cteContext.getReuse("cte1").get();

        // PlanChecker.from(connectContext, cte1AnalyzedPlan)
        //         .matchesFromRoot(
        //             logicalSubQueryAlias(
        //                 logicalProject()
        //                 .when(p -> p.getProjects().size() == 2
        //                         && p.getProjects().get(0).getName().equals("s_suppkey")
        //                         && p.getProjects().get(0).getExprId().asInt() == 14
        //                         && p.getProjects().get(0).getQualifier().equals(ImmutableList.of("default_cluster:test", "supplier"))
        //                         && p.getProjects().get(1).getName().equals("s_nation")
        //                         && p.getProjects().get(1).getExprId().asInt() == 18
        //                         && p.getProjects().get(1).getQualifier().equals(ImmutableList.of("default_cluster:test", "supplier"))
        //                 )
        //             )
        //             .when(a -> a.getAlias().equals("cte1"))
        //             .when(a -> a.getOutput().size() == 2
        //                     && a.getOutput().get(0).getName().equals("skey")
        //                     && a.getOutput().get(0).getExprId().asInt() == 14
        //                     && a.getOutput().get(0).getQualifier().equals(ImmutableList.of("cte1"))
        //                     && a.getOutput().get(1).getName().equals("s_nation")
        //                     && a.getOutput().get(1).getExprId().asInt() == 18
        //                     && a.getOutput().get(1).getQualifier().equals(ImmutableList.of("cte1"))
        //             )
        //         );
    }

    @Test
    public void testCTEInHavingAndSubquery() {

        PlanChecker.from(connectContext)
                .analyze(sql3)
                .applyBottomUp(new PullUpProjectUnderApply())
                .applyBottomUp(new UnCorrelatedApplyFilter())
                .applyBottomUp(new InApplyToJoin())
                .matches(
                    logicalCTE(
                            logicalFilter(
                                    logicalProject(
                                            logicalJoin(
                                                    logicalAggregate(
                                                            logicalCTEConsumer()
                                                    ), logicalProject(
                                                            logicalCTEConsumer())
                                            )
                                    )

                            )
                    )
                );
    }

    @Test
    public void testCTEWithAlias() {
        PlanChecker.from(connectContext)
                .analyze(sql4)
                .matchesFromRoot(
                        logicalCTE(
                                logicalProject(
                                        logicalJoin(
                                                logicalCTEConsumer(),
                                                logicalCTEConsumer()
                                        )
                                )
                        )
                );
    }

    @Test
    public void testCTEWithAnExistedTableOrViewName() {
        PlanChecker.from(connectContext)
                .analyze(sql5)
                .matchesFromRoot(
                        logicalCTE(
                                logicalProject(
                                        logicalCTEConsumer()
                                )
                        )
                );

    }


    /* ********************************************************************************************
     * Test CTE Exceptions
     * ******************************************************************************************** */

    @Test
    public void testCTEExceptionOfDuplicatedColumnAlias() {
        String sql = "WITH cte1 (a1, A1) AS (SELECT * FROM supplier)"
                + "SELECT * FROM cte1";

        NereidsException exception = Assertions.assertThrows(NereidsException.class, () -> {
            PlanChecker.from(connectContext).checkPlannerResult(sql);
        }, "Not throw expected exception.");
        Assertions.assertTrue(exception.getMessage().contains("Duplicated CTE column alias: [a1] in CTE [cte1]"));
    }

    @Test
    public void testCTEExceptionOfColumnAliasSize() {
        String sql = "WITH cte1 (a1, a2) AS "
                + "(SELECT s_suppkey FROM supplier)"
                + "SELECT * FROM cte1";

        NereidsException exception = Assertions.assertThrows(NereidsException.class, () -> {
            PlanChecker.from(connectContext).checkPlannerResult(sql);
        }, "Not throw expected exception.");
        System.out.println(exception.getMessage());
        Assertions.assertTrue(exception.getMessage().contains("CTE [cte1] returns 2 columns, "
                + "but 1 labels were specified."));
    }

    @Test
    public void testCTEExceptionOfReferenceInWrongOrder() {
        String sql = "WITH cte1 AS (SELECT * FROM cte2), "
                + "cte2 AS (SELECT * FROM supplier)"
                + "SELECT * FROM cte1, cte2";

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> {
            PlanChecker.from(connectContext).checkPlannerResult(sql);
        }, "Not throw expected exception.");
        Assertions.assertTrue(exception.getMessage().contains("[cte2] does not exist in database"));
    }

    @Test
    public void testCTEExceptionOfErrorInUnusedCTE() {
        String sql = "WITH cte1 AS (SELECT * FROM not_existed_table)"
                + "SELECT * FROM supplier";

        RuntimeException exception = Assertions.assertThrows(RuntimeException.class, () -> {
            PlanChecker.from(connectContext).checkPlannerResult(sql);
        }, "Not throw expected exception.");
        Assertions.assertTrue(exception.getMessage().contains("[not_existed_table] does not exist in database"));
    }

    @Test
    public void testCTEExceptionOfDuplicatedCTEName() {
        String sql = "WITH cte1 AS (SELECT * FROM supplier), "
                    + "cte1 AS (SELECT * FROM part)"
                    + "SELECT * FROM cte1";

        AnalysisException exception = Assertions.assertThrows(AnalysisException.class, () -> {
            PlanChecker.from(connectContext).analyze(sql);
        }, "Not throw expected exception.");
        Assertions.assertTrue(exception.getMessage().contains("[cte1] cannot be used more than once"));
    }

    @Test
    public void testDifferenceRelationId() {
        PlanChecker.from(connectContext)
                .analyze("with s as (select * from supplier) select * from s as s1, s as s2")
                .matchesFromRoot(
                    logicalCTE(
                            logicalProject(
                                    logicalJoin(
                                            logicalSubQueryAlias(
                                                    logicalCTEConsumer()
                                            ),
                                            logicalSubQueryAlias(
                                                    logicalCTEConsumer()
                                            )
                                    )
                            )
                    )
                );
    }
}
