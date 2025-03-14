/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.rest.service.task;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.security.UserGroupInformation;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.NLocalFileMetadataTestCase;
import org.apache.kylin.common.util.TimeUtil;
import org.apache.kylin.junit.TimeZoneTestRunner;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.favorite.AccelerateRuleUtil;
import org.apache.kylin.metadata.favorite.AsyncAccelerationTask;
import org.apache.kylin.metadata.favorite.AsyncTaskManager;
import org.apache.kylin.metadata.favorite.QueryHistoryIdOffset;
import org.apache.kylin.metadata.favorite.QueryHistoryIdOffsetManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.metadata.query.QueryHistory;
import org.apache.kylin.metadata.query.QueryHistoryInfo;
import org.apache.kylin.metadata.query.QueryMetrics;
import org.apache.kylin.metadata.query.RDBMSQueryHistoryDAO;
import org.apache.kylin.rest.service.IUserGroupService;
import org.apache.kylin.rest.service.NUserGroupService;
import org.apache.kylin.rest.service.task.QueryHistoryTaskScheduler.QueryHistoryMetaUpdateRunner;
import org.apache.kylin.rest.util.SpringContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.modules.junit4.PowerMockRunnerDelegate;
import org.springframework.context.ApplicationContext;
import org.springframework.security.acls.domain.PermissionFactory;
import org.springframework.security.acls.model.PermissionGrantingStrategy;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.kylin.guava30.shaded.common.collect.ImmutableMap;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;

import lombok.val;
import lombok.var;

@RunWith(PowerMockRunner.class)
@PowerMockRunnerDelegate(TimeZoneTestRunner.class)
@PrepareForTest({ SpringContext.class, UserGroupInformation.class })
@PowerMockIgnore("javax.management.*")
public class QueryHistoryTaskSchedulerTest extends NLocalFileMetadataTestCase {
    private static final String PROJECT = "default";
    private static final String DATAFLOW = "89af4ee2-2cdb-4b07-b39e-4c29856309aa";
    private static final String LAYOUT1 = "20000000001";
    private static final String LAYOUT2 = "1000001";
    private static final String LAYOUT3 = "30001";
    private static final String LAYOUT4 = "40001";
    private static final Long QUERY_TIME = 1586760398338L;

    private QueryHistoryTaskScheduler qhAccelerateScheduler;

    @Mock
    private final IUserGroupService userGroupService = Mockito.spy(NUserGroupService.class);

    @Before
    public void setUp() throws Exception {
        PowerMockito.mockStatic(SpringContext.class);
        PowerMockito.mockStatic(UserGroupInformation.class);
        UserGroupInformation userGroupInformation = Mockito.mock(UserGroupInformation.class);
        PowerMockito.when(UserGroupInformation.getCurrentUser()).thenReturn(userGroupInformation);
        createTestMetadata();
        ApplicationContext applicationContext = PowerMockito.mock(ApplicationContext.class);
        PowerMockito.when(SpringContext.getApplicationContext()).thenReturn(applicationContext);
        PowerMockito.when(SpringContext.getBean(PermissionFactory.class))
                .thenReturn(PowerMockito.mock(PermissionFactory.class));
        PowerMockito.when(SpringContext.getBean(PermissionGrantingStrategy.class))
                .thenReturn(PowerMockito.mock(PermissionGrantingStrategy.class));
        qhAccelerateScheduler = Mockito.spy(new QueryHistoryTaskScheduler(PROJECT));
        ReflectionTestUtils.setField(qhAccelerateScheduler, "userGroupService", userGroupService);
    }

    @After
    public void tearDown() throws Exception {
        cleanupTestMetadata();
    }

    @Test
    public void testQueryAcc() {
        getTestConfig().setProperty("kylin.metadata.semi-automatic-mode", "true");
        qhAccelerateScheduler.queryHistoryDAO = Mockito.mock(RDBMSQueryHistoryDAO.class);
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(queryHistories()).thenReturn(null);

        Mockito.when(qhAccelerateScheduler.accelerateRuleUtil.findMatchedCandidate(Mockito.anyString(),
                Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenReturn(queryHistories());

        // before update id offset
        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        Assert.assertEquals(0, idOffsetManager.get().getOffset());

        // run update
        QueryHistoryTaskScheduler.QueryHistoryAccelerateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryAccelerateRunner(false);
        queryHistoryAccelerateRunner.run();

        // after update id offset
        Assert.assertEquals(8, idOffsetManager.get().getOffset());

    }

    @Test
    public void testQueryAccResetOffset() {
        getTestConfig().setProperty("kylin.metadata.semi-automatic-mode", "true");
        qhAccelerateScheduler.queryHistoryDAO = Mockito.spy(RDBMSQueryHistoryDAO.getInstance());
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(Lists.newArrayList());

        // before update id offset
        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        QueryHistoryIdOffset queryHistoryIdOffset = idOffsetManager.get();
        queryHistoryIdOffset.setOffset(999L);
        queryHistoryIdOffset.setStatMetaUpdateOffset(999L);
        idOffsetManager.save(queryHistoryIdOffset);
        Assert.assertEquals(999L, idOffsetManager.get().getOffset());
        // run update
        QueryHistoryTaskScheduler.QueryHistoryAccelerateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryAccelerateRunner(false);
        queryHistoryAccelerateRunner.run();
        // after auto reset offset
        Assert.assertEquals(0L, idOffsetManager.get().getOffset());
    }

    @Test
    public void testQueryAccNotResetOffset() {
        qhAccelerateScheduler.queryHistoryDAO = Mockito.spy(RDBMSQueryHistoryDAO.getInstance());
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(Lists.newArrayList());
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.getQueryHistoryMaxId(Mockito.anyString())).thenReturn(12L);
        // before update id offset
        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        QueryHistoryIdOffset queryHistoryIdOffset = idOffsetManager.get();
        queryHistoryIdOffset.setOffset(9L);
        queryHistoryIdOffset.setStatMetaUpdateOffset(9L);
        idOffsetManager.save(queryHistoryIdOffset);
        Assert.assertEquals(9L, idOffsetManager.get().getOffset());

        // run update
        QueryHistoryTaskScheduler.QueryHistoryAccelerateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryAccelerateRunner(false);
        queryHistoryAccelerateRunner.run();
        // after auto reset offset
        Assert.assertEquals(9L, idOffsetManager.get().getOffset());
    }

    @Test
    public void testUpdateMetadata() {
        qhAccelerateScheduler.queryHistoryDAO = Mockito.mock(RDBMSQueryHistoryDAO.class);
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(queryHistories()).thenReturn(null);

        // before update dataflow usage, layout usage and last query time
        NDataflow dataflow = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), PROJECT)
                .getDataflow(DATAFLOW);
        Assert.assertEquals(3, dataflow.getQueryHitCount());
        Assert.assertNull(dataflow.getLayoutHitCount().get(20000000001L));
        Assert.assertNull(dataflow.getLayoutHitCount().get(1000001L));
        Assert.assertEquals(0L, dataflow.getLastQueryTime());

        // before update id offset
        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        Assert.assertEquals(0, idOffsetManager.get().getStatMetaUpdateOffset());

        // run update
        QueryHistoryTaskScheduler.QueryHistoryMetaUpdateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryMetaUpdateRunner();
        queryHistoryAccelerateRunner.run();

        // after update dataflow usage, layout usage and last query time
        dataflow = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), PROJECT).getDataflow(DATAFLOW);
        Assert.assertEquals(6, dataflow.getQueryHitCount());
        Assert.assertEquals(2, dataflow.getLayoutHitCount().get(20000000001L).getDateFrequency()
                .get(TimeUtil.getDayStart(QUERY_TIME)).intValue());
        Assert.assertEquals(1, dataflow.getLayoutHitCount().get(1000001L).getDateFrequency()
                .get(TimeUtil.getDayStart(QUERY_TIME)).intValue());
        Assert.assertEquals(1586760398338L, dataflow.getLastQueryTime());

        // update snapshot usage
        NTableMetadataManager tableMetadataManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(),
                PROJECT);
        Assert.assertEquals(1, tableMetadataManager.getOrCreateTableExt("DEFAULT.TEST_ACCOUNT").getSnapshotHitCount());
        Assert.assertEquals(1, tableMetadataManager.getOrCreateTableExt("DEFAULT.TEST_ORDER").getSnapshotHitCount());

        // after update id offset
        Assert.assertEquals(8, idOffsetManager.get().getStatMetaUpdateOffset());
    }

    @Test
    public void testUpdateLastQueryTime()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {

        // before update dataflow usage, layout usage and last query time
        NDataflow dataflow = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), PROJECT)
                .getDataflow(DATAFLOW);
        Assert.assertEquals(3, dataflow.getQueryHitCount());
        Assert.assertNull(dataflow.getLayoutHitCount().get(20000000001L));
        Assert.assertNull(dataflow.getLayoutHitCount().get(1000001L));
        Assert.assertEquals(0L, dataflow.getLastQueryTime());

        val queryHistoryAccelerateRunner = qhAccelerateScheduler.new QueryHistoryMetaUpdateRunner();
        Class<? extends QueryHistoryMetaUpdateRunner> clazz = queryHistoryAccelerateRunner.getClass();
        Method method = clazz.getDeclaredMethod("updateLastQueryTime", Map.class, String.class);
        method.setAccessible(true);
        method.invoke(queryHistoryAccelerateRunner, ImmutableMap.of("aaa", 100L), PROJECT);
        method.invoke(queryHistoryAccelerateRunner, ImmutableMap.of(DATAFLOW, 100L), PROJECT);
        method.setAccessible(false);

        NDataflow dataflow1 = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), PROJECT)
                .getDataflow(DATAFLOW);
        long lastQueryTime = dataflow1.getLastQueryTime();
        Assert.assertEquals(100L, lastQueryTime);
    }

    @Test
    public void testUpdateMetadataWithStringRealization() {
        qhAccelerateScheduler.queryHistoryDAO = Mockito.mock(RDBMSQueryHistoryDAO.class);
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(queryHistoriesWithStringRealization())
                .thenReturn(null);

        // before update dataflow usage, layout usage and last query time
        NDataflow dataflow = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), PROJECT)
                .getDataflow(DATAFLOW);
        Assert.assertEquals(3, dataflow.getQueryHitCount());
        Assert.assertNull(dataflow.getLayoutHitCount().get(20000000001L));
        Assert.assertNull(dataflow.getLayoutHitCount().get(1000001L));
        Assert.assertEquals(0L, dataflow.getLastQueryTime());

        // before update id offset
        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        Assert.assertEquals(0, idOffsetManager.get().getStatMetaUpdateOffset());

        // run update
        QueryHistoryTaskScheduler.QueryHistoryMetaUpdateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryMetaUpdateRunner();
        queryHistoryAccelerateRunner.run();

        // after update dataflow usage, layout usage and last query time
        dataflow = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), PROJECT).getDataflow(DATAFLOW);
        Assert.assertEquals(6, dataflow.getQueryHitCount());
        Assert.assertEquals(2, dataflow.getLayoutHitCount().get(20000000001L).getDateFrequency()
                .get(TimeUtil.getDayStart(QUERY_TIME)).intValue());
        Assert.assertEquals(1, dataflow.getLayoutHitCount().get(1000001L).getDateFrequency()
                .get(TimeUtil.getDayStart(QUERY_TIME)).intValue());
        Assert.assertEquals(1586760398338L, dataflow.getLastQueryTime());

        // after update id offset
        Assert.assertEquals(8, idOffsetManager.get().getStatMetaUpdateOffset());
    }

    @Test
    public void testNotUpdateMetadataForUserTriggered() {
        qhAccelerateScheduler.queryHistoryDAO = Mockito.mock(RDBMSQueryHistoryDAO.class);
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(queryHistories()).thenReturn(null);
        Mockito.when(qhAccelerateScheduler.accelerateRuleUtil.findMatchedCandidate(Mockito.anyString(),
                Mockito.anyList(), Mockito.anyMap(), Mockito.anyList())).thenReturn(queryHistories());

        // before update id offset
        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        Assert.assertEquals(0, idOffsetManager.get().getOffset());

        // update sync-acceleration-task to running, then check auto-run will fail
        AsyncTaskManager manager = AsyncTaskManager.getInstance(getTestConfig(), PROJECT);
        AsyncAccelerationTask asyncTask = (AsyncAccelerationTask) manager.get(AsyncTaskManager.ASYNC_ACCELERATION_TASK);
        asyncTask.setAlreadyRunning(true);
        manager.save(asyncTask);
        AsyncAccelerationTask update = (AsyncAccelerationTask) manager.get(AsyncTaskManager.ASYNC_ACCELERATION_TASK);
        Assert.assertTrue(update.isAlreadyRunning());

        // run update
        QueryHistoryTaskScheduler.QueryHistoryAccelerateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryAccelerateRunner(false);
        queryHistoryAccelerateRunner.run();

        Assert.assertEquals(0, idOffsetManager.get().getOffset());
    }

    @Test
    public void testQueryHitSnapshotCount() {
        QueryHistoryTaskScheduler.QueryHistoryMetaUpdateRunner queryHistoryAccelerateRunner = qhAccelerateScheduler.new QueryHistoryMetaUpdateRunner();
        TableExtDesc tableExtDesc = new TableExtDesc();
        tableExtDesc.setSnapshotHitCount(10);
        tableExtDesc.setIdentity("123");
        Map<TableExtDesc, Integer> map = Maps.newHashMap();
        map.put(tableExtDesc, 1);
        ReflectionTestUtils.invokeMethod(queryHistoryAccelerateRunner, "incQueryHitSnapshotCount", map, PROJECT);
    }

    @Test
    public void testBatchUpdate() {
        qhAccelerateScheduler.queryHistoryDAO = Mockito.mock(RDBMSQueryHistoryDAO.class);
        qhAccelerateScheduler.accelerateRuleUtil = Mockito.mock(AccelerateRuleUtil.class);
        Mockito.when(qhAccelerateScheduler.queryHistoryDAO.queryQueryHistoriesByIdOffset(Mockito.anyLong(),
                Mockito.anyInt(), Mockito.anyString())).thenReturn(queryHistories()).thenReturn(queryHistories());

        // run update
        QueryHistoryTaskScheduler.QueryHistoryMetaUpdateRunner queryHistoryAccelerateRunner = //
                qhAccelerateScheduler.new QueryHistoryMetaUpdateRunner();
        queryHistoryAccelerateRunner.run();

        QueryHistoryIdOffsetManager idOffsetManager = QueryHistoryIdOffsetManager.getInstance(getTestConfig(), PROJECT);
        Assert.assertEquals(8, idOffsetManager.get().getStatMetaUpdateOffset());

        queryHistoryAccelerateRunner.run();
        Assert.assertEquals(16, idOffsetManager.get().getStatMetaUpdateOffset());
    }

    @Test
    public void testUpdateStatMeta() {
        QueryHistoryTaskScheduler taskScheduler = new QueryHistoryTaskScheduler("streaming_test");
        QueryHistoryTaskScheduler.QueryHistoryMetaUpdateRunner metaUpdateRunner = taskScheduler.new QueryHistoryMetaUpdateRunner();
        NDataflowManager manager = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), "streaming_test");
        {
            var dataflow = manager.getDataflow("334671fd-e383-4fc9-b5c2-94fce832f77a");
            Assert.assertTrue(dataflow.getLayoutHitCount().isEmpty());
            ReflectionTestUtils.invokeMethod(metaUpdateRunner, "updateStatMeta", batchModelQueryHistory());
            dataflow = manager.getDataflow("334671fd-e383-4fc9-b5c2-94fce832f77a");
            Assert.assertEquals(1, countDateFrequency(dataflow, LAYOUT3));
        }
        {
            var batchDataflow = manager.getDataflow("334671fd-e383-4fc9-b5c2-94fce832f77a");
            var streamingDataflow = manager.getDataflow("b05034a8-c037-416b-aa26-9e6b4a41ee40");

            Assert.assertFalse(batchDataflow.getLayoutHitCount().containsKey(Long.parseLong(LAYOUT4)));
            Assert.assertFalse(streamingDataflow.getLayoutHitCount().containsKey(Long.parseLong(LAYOUT3)));

            ReflectionTestUtils.invokeMethod(metaUpdateRunner, "updateStatMeta", fusionModelQueryHistory());
            batchDataflow = manager.getDataflow("334671fd-e383-4fc9-b5c2-94fce832f77a");
            streamingDataflow = manager.getDataflow("b05034a8-c037-416b-aa26-9e6b4a41ee40");

            Assert.assertEquals(1, countDateFrequency(batchDataflow, LAYOUT4));
            Assert.assertEquals(1, countDateFrequency(streamingDataflow, LAYOUT3));
        }
        {
            var streamingDataflow = manager.getDataflow("b05034a8-c037-416b-aa26-9e6b4a41ee40");
            Assert.assertEquals(1, countDateFrequency(streamingDataflow, LAYOUT3));
            ReflectionTestUtils.invokeMethod(metaUpdateRunner, "updateStatMeta", streamingModelQueryHistory());
            streamingDataflow = manager.getDataflow("b05034a8-c037-416b-aa26-9e6b4a41ee40");
            Assert.assertEquals(2, countDateFrequency(streamingDataflow, LAYOUT3));
        }
    }

    private int countDateFrequency(NDataflow dataflow, String layout) {
        return dataflow.getLayoutHitCount().get(Long.parseLong(layout)).getDateFrequency().values().stream()
                .mapToInt(Integer::intValue).sum();
    }

    private List<QueryHistory> queryHistories() {
        QueryHistory queryHistory1 = new QueryHistory();
        queryHistory1.setSqlPattern("select * from sql1");
        queryHistory1.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory1.setDuration(1000L);
        queryHistory1.setQueryTime(1001);
        queryHistory1.setEngineType("CONSTANTS");
        queryHistory1.setId(1);

        QueryHistory queryHistory2 = new QueryHistory();
        queryHistory2.setSqlPattern("select * from sql2");
        queryHistory2.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory2.setDuration(1000L);
        queryHistory2.setQueryTime(1002);
        queryHistory2.setEngineType("HIVE");
        queryHistory2.setId(2);

        QueryHistory queryHistory3 = new QueryHistory();
        queryHistory3.setSqlPattern("select * from sql3");
        queryHistory3.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory3.setDuration(1000L);
        queryHistory3.setQueryTime(1003);
        queryHistory3.setEngineType("NATIVE");
        queryHistory3.setId(3);

        QueryHistory queryHistory4 = new QueryHistory();
        queryHistory4.setSqlPattern("select * from sql3");
        queryHistory4.setQueryStatus(QueryHistory.QUERY_HISTORY_FAILED);
        queryHistory4.setDuration(1000L);
        queryHistory4.setQueryTime(1004);
        queryHistory4.setEngineType("HIVE");
        queryHistory4.setId(4);

        QueryHistory queryHistory5 = new QueryHistory();
        queryHistory5.setSqlPattern("SELECT \"LSTG_FORMAT_NAME\"\n" + "FROM \"KYLIN_SALES\"");
        queryHistory5.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory5.setDuration(1000L);
        queryHistory5.setQueryTime(QUERY_TIME);
        queryHistory5.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo5 = new QueryHistoryInfo();
        queryHistoryInfo5.setRealizationMetrics(Lists.newArrayList(
                new QueryMetrics.RealizationMetrics(LAYOUT1, "Table Index", DATAFLOW, Lists.newArrayList())));
        queryHistory5.setQueryHistoryInfo(queryHistoryInfo5);
        queryHistory5.setId(5);

        QueryHistory queryHistory6 = new QueryHistory();
        queryHistory6.setSqlPattern("SELECT \"LSTG_FORMAT_NAME\"\n" + "FROM \"KYLIN_SALES\"");
        queryHistory6.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory6.setDuration(1000L);
        queryHistory6.setQueryTime(QUERY_TIME);
        queryHistory6.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo6 = new QueryHistoryInfo();
        queryHistoryInfo6.setRealizationMetrics(Lists.newArrayList(
                new QueryMetrics.RealizationMetrics(LAYOUT1, "Table Index", DATAFLOW, Lists.newArrayList())));
        queryHistory6.setQueryHistoryInfo(queryHistoryInfo6);
        queryHistory6.setId(6);

        QueryHistory queryHistory7 = new QueryHistory();
        queryHistory7.setSqlPattern("SELECT count(*) FROM \"KYLIN_SALES\" group by LSTG_FORMAT_NAME");
        queryHistory7.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory7.setDuration(1000L);
        queryHistory7.setQueryTime(QUERY_TIME);
        queryHistory7.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo7 = new QueryHistoryInfo();
        queryHistoryInfo7.setQuerySnapshots(Lists.newArrayList(Lists.newArrayList("DEFAULT.TEST_ACCOUNT"),
                Lists.newArrayList("DEFAULT.TEST_ORDER")));
        queryHistoryInfo7.setRealizationMetrics(Lists.newArrayList(
                new QueryMetrics.RealizationMetrics(LAYOUT2, "Table Index", DATAFLOW, Lists.newArrayList())));
        queryHistory7.setQueryHistoryInfo(queryHistoryInfo7);
        queryHistory7.setId(7);

        QueryHistory queryHistory8 = new QueryHistory();
        queryHistory8.setSqlPattern("SELECT count(*) FROM \"KYLIN_SALES\" group by LSTG_FORMAT_NAME");
        queryHistory8.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory8.setDuration(1000L);
        queryHistory8.setQueryTime(QUERY_TIME);
        queryHistory8.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo8 = new QueryHistoryInfo();
        queryHistoryInfo8.setRealizationMetrics(
                Lists.newArrayList(new QueryMetrics.RealizationMetrics(null, null, DATAFLOW, Lists.newArrayList())));
        queryHistory8.setQueryHistoryInfo(queryHistoryInfo8);
        queryHistory8.setId(8);

        List<QueryHistory> histories = Lists.newArrayList(queryHistory1, queryHistory2, queryHistory3, queryHistory4,
                queryHistory5, queryHistory6, queryHistory7, queryHistory8);

        for (QueryHistory history : histories) {
            history.setId(startOffset + history.getId());
        }
        startOffset += histories.size();

        return histories;
    }

    private List<QueryHistory> queryHistoriesWithStringRealization() {
        QueryHistory queryHistory1 = new QueryHistory();
        queryHistory1.setSqlPattern("select * from sql1");
        queryHistory1.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory1.setDuration(1000L);
        queryHistory1.setQueryTime(1001);
        queryHistory1.setEngineType("CONSTANTS");
        queryHistory1.setId(1);

        QueryHistory queryHistory2 = new QueryHistory();
        queryHistory2.setSqlPattern("select * from sql2");
        queryHistory2.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory2.setDuration(1000L);
        queryHistory2.setQueryTime(1002);
        queryHistory2.setEngineType("HIVE");
        queryHistory2.setId(2);

        QueryHistory queryHistory3 = new QueryHistory();
        queryHistory3.setSqlPattern("select * from sql3");
        queryHistory3.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory3.setDuration(1000L);
        queryHistory3.setQueryTime(1003);
        queryHistory3.setEngineType("NATIVE");
        queryHistory3.setId(3);

        QueryHistory queryHistory4 = new QueryHistory();
        queryHistory4.setSqlPattern("select * from sql3");
        queryHistory4.setQueryStatus(QueryHistory.QUERY_HISTORY_FAILED);
        queryHistory4.setDuration(1000L);
        queryHistory4.setQueryTime(1004);
        queryHistory4.setEngineType("HIVE");
        queryHistory4.setId(4);

        QueryHistory queryHistory5 = new QueryHistory();
        queryHistory5.setSqlPattern("SELECT \"LSTG_FORMAT_NAME\"\n" + "FROM \"KYLIN_SALES\"");
        queryHistory5.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory5.setDuration(1000L);
        queryHistory5.setQueryTime(QUERY_TIME);
        queryHistory5.setEngineType("NATIVE");
        queryHistory5.setQueryRealizations(DATAFLOW + "#" + LAYOUT1 + "#Table Index#[]");
        queryHistory5.setId(5);

        QueryHistory queryHistory6 = new QueryHistory();
        queryHistory6.setSqlPattern("SELECT \"LSTG_FORMAT_NAME\"\n" + "FROM \"KYLIN_SALES\"");
        queryHistory6.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory6.setDuration(1000L);
        queryHistory6.setQueryTime(QUERY_TIME);
        queryHistory6.setEngineType("NATIVE");
        queryHistory6.setQueryRealizations(DATAFLOW + "#" + LAYOUT1 + "#Table Index#[]");
        queryHistory6.setId(6);

        QueryHistory queryHistory7 = new QueryHistory();
        queryHistory7.setSqlPattern("SELECT count(*) FROM \"KYLIN_SALES\" group by LSTG_FORMAT_NAME");
        queryHistory7.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory7.setDuration(1000L);
        queryHistory7.setQueryTime(QUERY_TIME);
        queryHistory7.setEngineType("NATIVE");
        queryHistory7.setQueryRealizations(DATAFLOW + "#" + LAYOUT2 + "#Agg Index");
        queryHistory7.setId(7);

        QueryHistory queryHistory8 = new QueryHistory();
        queryHistory8.setSqlPattern("SELECT count(*) FROM \"KYLIN_SALES\" group by LSTG_FORMAT_NAME");
        queryHistory8.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory8.setDuration(1000L);
        queryHistory8.setQueryTime(QUERY_TIME);
        queryHistory8.setEngineType("NATIVE");
        queryHistory8.setQueryRealizations(DATAFLOW + "#null" + "#null");
        queryHistory8.setId(8);

        List<QueryHistory> histories = Lists.newArrayList(queryHistory1, queryHistory2, queryHistory3, queryHistory4,
                queryHistory5, queryHistory6, queryHistory7, queryHistory8);

        for (QueryHistory history : histories) {
            history.setId(startOffset + history.getId());
        }
        startOffset += histories.size();

        return histories;
    }

    /**
     * sql match batch model of fusion model
     * @return
     */
    private List<QueryHistory> batchModelQueryHistory() {
        String fusionModelId = "b05034a8-c037-416b-aa26-9e6b4a41ee40";
        QueryHistory queryHistory1 = new QueryHistory();
        queryHistory1.setSqlPattern("SELECT MAX(LO_ORDERKEY) FROM SSB.KAFKA_FUSION");
        queryHistory1.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory1.setDuration(1000L);
        queryHistory1.setQueryTime(QUERY_TIME);
        queryHistory1.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo1 = new QueryHistoryInfo();
        queryHistoryInfo1.setRealizationMetrics(Lists.newArrayList(
                new QueryMetrics.RealizationMetrics(LAYOUT3, "Agg Index", fusionModelId, Lists.newArrayList())));
        queryHistory1.setQueryHistoryInfo(queryHistoryInfo1);
        queryHistory1.setId(10);
        return Lists.newArrayList(queryHistory1);
    }

    /**
     * sql match both batch model and streaming model in fusion model
     * @return
     */
    private List<QueryHistory> fusionModelQueryHistory() {
        String fusionModelId = "b05034a8-c037-416b-aa26-9e6b4a41ee40";
        String batchModelId = "334671fd-e383-4fc9-b5c2-94fce832f77a";
        QueryHistory queryHistory1 = new QueryHistory();
        queryHistory1.setSqlPattern("SELECT MAX(LO_ORDERKEY) FROM SSB.KAFKA_FUSION");
        queryHistory1.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory1.setDuration(1000L);
        queryHistory1.setQueryTime(QUERY_TIME);
        queryHistory1.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo1 = new QueryHistoryInfo();
        QueryMetrics.RealizationMetrics streamingRealizationMetric = new QueryMetrics.RealizationMetrics(LAYOUT3,
                "Agg Index", fusionModelId, Lists.newArrayList());
        streamingRealizationMetric.setStreamingLayout(true);
        queryHistoryInfo1.setRealizationMetrics(Lists.newArrayList(streamingRealizationMetric,
                new QueryMetrics.RealizationMetrics(LAYOUT4, "Agg Index", batchModelId, Lists.newArrayList())));
        queryHistory1.setQueryHistoryInfo(queryHistoryInfo1);
        queryHistory1.setId(11);
        return Lists.newArrayList(queryHistory1);
    }

    /**
     * sql match streaming model in fusion model
     * @return
     */
    private List<QueryHistory> streamingModelQueryHistory() {
        String fusionModelId = "b05034a8-c037-416b-aa26-9e6b4a41ee40";
        QueryHistory queryHistory1 = new QueryHistory();
        queryHistory1.setSqlPattern("SELECT MAX(LO_ORDERKEY) FROM SSB.KAFKA_FUSION");
        queryHistory1.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory1.setDuration(1000L);
        queryHistory1.setQueryTime(QUERY_TIME);
        queryHistory1.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo1 = new QueryHistoryInfo();
        QueryMetrics.RealizationMetrics streamingRealizationMetric = new QueryMetrics.RealizationMetrics(LAYOUT3,
                "Agg Index", fusionModelId, Lists.newArrayList());
        streamingRealizationMetric.setStreamingLayout(true);
        queryHistoryInfo1.setRealizationMetrics(Lists.newArrayList(streamingRealizationMetric));
        queryHistory1.setQueryHistoryInfo(queryHistoryInfo1);
        queryHistory1.setId(10);

        QueryHistory queryHistory2 = new QueryHistory();
        queryHistory2.setSqlPattern("SELECT MAX(LO_ORDERKEY) FROM SSB.KAFKA_FUSION");
        queryHistory2.setQueryStatus(QueryHistory.QUERY_HISTORY_SUCCEEDED);
        queryHistory2.setDuration(1000L);
        queryHistory2.setQueryTime(QUERY_TIME);
        queryHistory2.setEngineType("NATIVE");
        QueryHistoryInfo queryHistoryInfo2 = new QueryHistoryInfo();
        QueryMetrics.RealizationMetrics realizationMetric = new QueryMetrics.RealizationMetrics(LAYOUT3, "Agg Index",
                "error", Lists.newArrayList());
        queryHistoryInfo2.setRealizationMetrics(Lists.newArrayList(realizationMetric));
        queryHistory2.setQueryHistoryInfo(queryHistoryInfo2);
        queryHistory2.setId(11);
        return Lists.newArrayList(queryHistory1, queryHistory2);
    }

    int startOffset = 0;

}