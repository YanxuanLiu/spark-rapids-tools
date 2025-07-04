/*
 * Copyright (c) 2024-2025, NVIDIA CORPORATION.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nvidia.spark.rapids.tool.analysis

import org.apache.spark.sql.execution.ui.SparkPlanGraphNode
import org.apache.spark.sql.rapids.tool.store.SQLPlanModel

// Class defines the SQLPlan context by implementations that walk through the SQLPlanInfo
class SQLPlanInfoContext(sqlPIGEntry: SQLPlanModel) {
  def getSQLPIGEntry: SQLPlanModel = sqlPIGEntry
}

/**
 * A trait that defines the logic to walk through the all the nodes a SQLPlanInfo
 * @tparam R the type of the SQLPlanInfoContext defined by teh actual implementation
 */
trait SparkSQLPlanInfoVisitor[R <: SQLPlanInfoContext] {
  // Given a SparkPlanInfo and its SQLID, it builds the SparkPlanGraph and returns
  // a SqlPlanInfoGraphEntry that holds the SQLID, SparkPlanInfo and SparkPlanGraph to be passed
  // as argument to the visitor
  protected def createSqlPIGEntry(sqlPlan: SQLPlanModel): SQLPlanModel = {
    sqlPlan
  }
  // Defines the logic to visit a single node
  protected def visitNode(sqlPlanCtxt: R, node: SparkPlanGraphNode): Unit

  // Given a SqlPlanInfoGraphEntry, it creates a context to be used by the visitor.
  // This is specific to the logic of the SQLPlan visitor
  protected def createPlanCtxtFromPIGEntry(sqlPIGEntry: SQLPlanModel): R

  // For each SQLPlan, it creates a context object to be passed down to all the nodes.
  private def createPlanCtxt(sqlPlan: SQLPlanModel): R = {
    val sqlPIGEntry = sqlPlan
    createPlanCtxtFromPIGEntry(sqlPIGEntry)
  }

  protected def walkPlan(planCtxt: R): Unit = {
    planCtxt.getSQLPIGEntry.getToolsPlanGraph.allNodes.foreach(visitNode(planCtxt, _))
  }

  // After a SQLPlan is visited, this method is called to process any additional logic.
  // Note that this extracted in a separate method to give more flexibility to extend the
  // implementation
  protected def postWalkPlan(planCtxt: R): Unit = {
    // do nothing by default
  }

  // Walks through all the SQLPlans in the given map
  def walkPlans(plans: Iterable[SQLPlanModel]): Unit = {
    for (plan <- plans) {
      val planCtxt = createPlanCtxt(plan)
      walkPlan(planCtxt)
      postWalkPlan(planCtxt)
    }
  }
}
