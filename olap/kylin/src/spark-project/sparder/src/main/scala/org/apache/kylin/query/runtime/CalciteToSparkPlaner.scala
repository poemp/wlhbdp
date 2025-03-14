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

package org.apache.kylin.query.runtime

import java.util

import org.apache.kylin.guava30.shaded.common.collect.Lists
import org.apache.kylin.engine.spark.utils.LogEx
import org.apache.calcite.DataContext
import org.apache.calcite.rel.{RelNode, RelVisitor}
import org.apache.kylin.common.KylinConfig
import org.apache.kylin.engine.spark.utils.LogEx
import org.apache.kylin.query.relnode.{KapAggregateRel, KapFilterRel, KapJoinRel, KapLimitRel, KapMinusRel, KapModelViewRel, KapNonEquiJoinRel, KapProjectRel, KapRel, KapSortRel, KapTableScan, KapUnionRel, KapValuesRel, KapWindowRel}
import org.apache.kylin.query.runtime.plan.{AggregatePlan, FilterPlan, LimitPlan, ProjectPlan, SortPlan, TableScanPlan, ValuesPlan, WindowPlan}
import org.apache.kylin.query.util.KapRelUtil
import org.apache.spark.sql.DataFrame

import scala.collection.JavaConverters._


class CalciteToSparkPlaner(dataContext: DataContext) extends RelVisitor with LogEx {
  private val stack = new util.Stack[DataFrame]()
  private val setOpStack = new util.Stack[Int]()
  private var unionLayer = 0
  private val dataframeCache = KylinConfig.getInstanceFromEnv.isDataFrameCacheEnabled

  // clear cache before any op
  cleanCache()

  override def visit(node: RelNode, ordinal: Int, parent: RelNode): Unit = {
    if (node.isInstanceOf[KapUnionRel]) {
      unionLayer = unionLayer + 1
    }
    if (node.isInstanceOf[KapUnionRel] || node.isInstanceOf[KapMinusRel]) {
      setOpStack.push(stack.size())
    }
    // skip non runtime joins children
    // cases to skip children visit
    // 1. current node is a KapJoinRel and is not a runtime join
    // 2. current node is a KapNonEquiJoinRel and is not a runtime join
    if (!(node.isInstanceOf[KapJoinRel] && !node.asInstanceOf[KapJoinRel].isRuntimeJoin) &&
      !(node.isInstanceOf[KapNonEquiJoinRel] && !node.asInstanceOf[KapNonEquiJoinRel].isRuntimeJoin)) {
      node.childrenAccept(this)
    }
    stack.push(node match {
      case rel: KapTableScan => convertTableScan(rel)
      case rel: KapFilterRel =>
        logTime("filter") { FilterPlan.filter(Lists.newArrayList(stack.pop()), rel, dataContext) }
      case rel: KapProjectRel =>
        logTime("project") {
          actionWith(rel) {
            ProjectPlan.select(Lists.newArrayList(stack.pop()), rel, dataContext)
          }
        }
      case rel: KapLimitRel =>
        logTime("limit") { LimitPlan.limit(Lists.newArrayList(stack.pop()), rel, dataContext) }
      case rel: KapSortRel =>
        logTime("sort") { SortPlan.sort(Lists.newArrayList(stack.pop()), rel, dataContext) }
      case rel: KapWindowRel =>
        logTime("window") { WindowPlan.window(Lists.newArrayList(stack.pop()), rel, dataContext) }
      case rel: KapAggregateRel =>
        logTime("agg") {
          actionWith(rel) {
            AggregatePlan.agg(Lists.newArrayList(stack.pop()), rel)
          }
        }
      case rel: KapJoinRel => convertJoinRel(rel)
      case rel: KapNonEquiJoinRel => convertNonEquiJoinRel(rel)
      case rel: KapUnionRel =>
        val size = setOpStack.pop()
        val java = Range(0, stack.size() - size).map(a => stack.pop()).asJava
        logTime("union") { plan.UnionPlan.union(Lists.newArrayList(java), rel, dataContext) }
      case rel: KapMinusRel =>
        val size = setOpStack.pop()
        logTime("minus") { plan.MinusPlan.minus(Range(0, stack.size() - size).map(a => stack.pop()).reverse, rel, dataContext) }
      case rel: KapValuesRel =>
        logTime("values") { ValuesPlan.values(rel) }
      case rel: KapModelViewRel =>
        logTime("modelview") { stack.pop() }
    })
    if (node.isInstanceOf[KapUnionRel]) {
      unionLayer = unionLayer - 1
    }
  }

  private def convertTableScan(rel: KapTableScan): DataFrame = {
    rel.getContext.genExecFunc(rel, rel.getTableName) match {
      case "executeLookupTableQuery" =>
        logTime("createLookupTable") {
          TableScanPlan.createLookupTable(rel)
        }
      case "executeOLAPQuery" =>
        logTime("createOLAPTable") {
          TableScanPlan.createOLAPTable(rel)
        }
      case "executeSimpleAggregationQuery" =>
        logTime("createSingleRow") {
          TableScanPlan.createSingleRow()
        }
      case "executeMetadataQuery" =>
        logTime("createMetadataTable") {
          TableScanPlan.createMetadataTable(rel)
        }
    }
  }

  private def convertJoinRel(rel: KapJoinRel): DataFrame = {
    if (!rel.isRuntimeJoin) {
      rel.getContext.genExecFunc(rel, "") match {
        case "executeMetadataQuery" =>
          logTime("createMetadataTable") {
            TableScanPlan.createMetadataTable(rel)
          }
        case _ =>
          logTime("join with table scan") {
            TableScanPlan.createOLAPTable(rel)
          }
      }
    } else {
      val right = stack.pop()
      val left = stack.pop()
      logTime("join") {
        plan.JoinPlan.join(Lists.newArrayList(left, right), rel)
      }
    }
  }

  private def convertNonEquiJoinRel(rel: KapNonEquiJoinRel): DataFrame = {
    if (!rel.isRuntimeJoin) {
      logTime("join with table scan") {
        TableScanPlan.createOLAPTable(rel)
      }
    } else {
      val right = stack.pop()
      val left = stack.pop()
      logTime("non-equi join") {
        plan.JoinPlan.nonEquiJoin(Lists.newArrayList(left, right), rel, dataContext)
      }
    }
  }

  def actionWith(rel: KapRel)(body: => DataFrame): DataFrame = {
    if (!dataframeCache) {
      body
    } else {
      actionWithCache(rel) {
        body
      }
    }
  }

  protected def actionWithCache(rel: KapRel)(body: => DataFrame): DataFrame = {
    var layoutId = 0L
    var modelId = ""
    var pruningSegmentHashCode = 0
    if (rel.getDigest == null) {
      body
    } else {
      try {
        val storageContext = rel.getContext.storageContext
        val layoutEntity = storageContext.getCandidate.getLayoutEntity
        val prunedSegments = storageContext.getPrunedSegments
        val tempTimeRange = new StringBuilder()
        if (prunedSegments != null) {
          prunedSegments.forEach(segment => {
            tempTimeRange.append(segment.getName)
          })
        }
        pruningSegmentHashCode = tempTimeRange.toString().hashCode
        layoutId = layoutEntity.getId
        modelId = layoutEntity.getModel.getId + "_" + pruningSegmentHashCode
      } catch {
        case e: Throwable => logWarning(s"Calculate layoutId modelId failed ex:${e.getMessage}")
      }
      rel.recomputeDigest()
      val digestWithoutId = KapRelUtil.getDigestWithoutRelNodeId(rel.getDigest, layoutId, modelId)
      if (unionLayer >= 1 && TableScanPlan.cacheDf.get.containsKey(digestWithoutId)) {
        stack.pop()
        logInfo("Happen Optimized from cache dataframe CacheKey:" + digestWithoutId)
        TableScanPlan.cacheDf.get.get(digestWithoutId)
      } else {
        val df = body
        if (unionLayer >= 1) {
          TableScanPlan.cacheDf.get.put(digestWithoutId, df)
        }
        df
      }
    }
  }

  def cleanCache(): Unit = {
    TableScanPlan.cacheDf.get().clear()
  }

  def getResult(): DataFrame = {
    stack.pop()
  }
}
