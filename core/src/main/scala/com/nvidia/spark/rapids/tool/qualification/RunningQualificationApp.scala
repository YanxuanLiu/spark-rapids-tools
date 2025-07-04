/*
 * Copyright (c) 2021-2025, NVIDIA CORPORATION.
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

package com.nvidia.spark.rapids.tool.qualification

import com.nvidia.spark.rapids.tool.{Platform, PlatformFactory}
import com.nvidia.spark.rapids.tool.analysis.AppSQLPlanAnalyzer
import com.nvidia.spark.rapids.tool.planparser.SQLPlanParser
import com.nvidia.spark.rapids.tool.qualification.QualOutputWriter.SQL_DESC_STR

import org.apache.spark.SparkEnv
import org.apache.spark.sql.rapids.tool.qualification._

/**
 * A Qualification tool application used for analyzing the application while it is
 * actively running. The qualification tool analyzes applications to determine if the
 * RAPIDS Accelerator for Apache Spark might be a good fit for those applications.
 * The standalone tool runs on Spark event logs after they have run. This class provides
 * an API to use with a running Spark application and processes events as they arrive.
 * This tool is intended to give the user a starting point and does not guarantee the
 * applications it scores high will actually be accelerated the most. When running
 * like this on a single application, the detailed output may be most useful to look
 * for potential issues and time spent in Dataframe operations.
 *
 * Please note that this will use additional memory so use with caution if using with a
 * long running application. The perSqlOnly option will allow reporting at the per
 * SQL query level without tracking all the Application information, but currently does
 * not cleanup. There is a cleanupSQL function that the user can force cleanup if required.
 *
 * Create the `RunningQualificationApp`:
 * {{{
 *   val qualApp = new com.nvidia.spark.rapids.tool.qualification.RunningQualificationApp()
 * }}}
 *
 * Get the event listener from it and install it as a Spark listener:
 * {{{
 *   val listener = qualApp.getEventListener
 *   spark.sparkContext.addSparkListener(listener)
 * }}}
 *
 * Run your queries and then get the Application summary or detailed output to see the results.
 * {{{
 *   // run your sql queries ...
 *   val summaryOutput = qualApp.getSummary()
 *   val detailedOutput = qualApp.getDetailed()
 * }}}
 *
 * If wanting per sql query output, run your queries and then get the output you are interested in.
 * {{{
 *   // run your sql queries ...
 *   val csvHeader = qualApp.getPerSqlCSVHeader
 *   val txtHeader = qualApp.getPerSqlTextHeader
 *   val (csvOut, txtOut) = qualApp.getPerSqlTextAndCSVSummary(sqlID)
 *   // print header and output wherever its useful
 * }}}
 *
 * @param perSqlOnly allows reporting at the SQL query level and doesn't track
 *                   the entire application
 */
class RunningQualificationApp(
    perSqlOnly: Boolean = false,
    pluginTypeChecker: PluginTypeChecker = new PluginTypeChecker(),
    platform: Platform = PlatformFactory.createInstance())
  extends QualificationAppInfo(None, None, pluginTypeChecker, reportSqlLevel = false,
    perSqlOnly, platform = platform) {
  // note we don't use the per sql reporting providing by QualificationAppInfo so we always
  // send down false for it

  // we don't know the max sql query name size so lets cap it at 100
  private val SQL_DESC_LENGTH = 100

  private lazy val perSqlHeadersAndSizes = {
      QualOutputWriter.getDetailedPerSqlHeaderStringsAndSizes(appId.size, SQL_DESC_LENGTH)
  }

  def this() = {
    this(false)
  }

  // since application is running, try to initialize current state
  private def initApp(): Unit = {
    val appName = SparkEnv.get.conf.get("spark.app.name", "")
    val appIdConf = SparkEnv.get.conf.getOption("spark.app.id")
    val appStartTime = SparkEnv.get.conf.get("spark.app.startTime", "-1")

    // start event doesn't happen so initialize it
    val newAppMeta =
      RunningAppMetadata(appName, appIdConf, appStartTime.toLong)
    appMetaData = Some(newAppMeta)
  }

  initApp()

  /**
   * Get the IDs of the SQL queries currently being tracked.
   * @return a sequence of SQL IDs
   */
  def getAvailableSqlIDs: Seq[Long] = {
    sqlIdToInfo.keys.toSeq
  }

  /**
   * Get the per SQL query header in CSV format.
   * @return a string with the header
   */
  def getPerSqlCSVHeader: String = {
    QualOutputWriter.constructDetailedHeader(perSqlHeadersAndSizes,
      QualOutputWriter.CSV_DELIMITER, false)
  }

  /**
   * Get the per SQL query header in TXT format for pretty printing.
   * @return a string with the header
   */
  def getPerSqlTextHeader: String = {
    QualOutputWriter.constructDetailedHeader(perSqlHeadersAndSizes,
      QualOutputWriter.TEXT_DELIMITER, true)
  }

  /**
   * Get the per SQL query header.
   * @return a string with the header
   */
  def getPerSqlHeader(delimiter: String,
      prettyPrint: Boolean, sqlDescLength: Int = SQL_DESC_LENGTH): String = {
    perSqlHeadersAndSizes(SQL_DESC_STR) = sqlDescLength
    QualOutputWriter.constructDetailedHeader(perSqlHeadersAndSizes, delimiter, prettyPrint)
  }

  /**
   * Get the per SQL query summary report in both Text and CSV format.
   * @param sqlID The sqlID of the query.
   * @return a tuple of the CSV summary followed by the Text summary.
   */
  def getPerSqlTextAndCSVSummary(sqlID: Long): (String, String) = {
    val sqlInfo = aggregatePerSQLStats(sqlID)
    val csvResult =
      constructPerSqlResult(sqlInfo, QualOutputWriter.CSV_DELIMITER,
        prettyPrint = false, escapeCSV = true)
    val textResult = constructPerSqlResult(sqlInfo, QualOutputWriter.TEXT_DELIMITER,
      prettyPrint = true)
    (csvResult, textResult)
  }

  /**
   * Get the per SQL query summary report for qualification for the specified sqlID.
   * @param sqlID The sqlID of the query.
   * @param delimiter The delimiter separating fields of the summary report.
   * @param prettyPrint Whether to include the delimiter at start and end and
   *                    add spacing so the data rows align with column headings.
   * @param sqlDescLength Maximum length to use for the SQL query description.
   * @return String containing the summary report, or empty string if its not available.
   */
  def getPerSQLSummary(sqlID: Long, delimiter: String = "|", prettyPrint: Boolean = true,
    sqlDescLength: Int = SQL_DESC_LENGTH, escapeCSV: Boolean = false): String = {
    val sqlInfo = aggregatePerSQLStats(sqlID)
    constructPerSqlResult(sqlInfo, delimiter, prettyPrint, sqlDescLength, escapeCSV)
  }

  private def constructPerSqlResult(
      sqlInfo: Option[EstimatedPerSQLSummaryInfo],
      delimiter: String,
      prettyPrint: Boolean,
      sqlDescLength: Int = SQL_DESC_LENGTH,
      escapeCSV: Boolean = false): String = {
    sqlInfo match {
      case Some(info) =>
        perSqlHeadersAndSizes(SQL_DESC_STR) = sqlDescLength
        QualOutputWriter.constructPerSqlSummaryInfo(info, perSqlHeadersAndSizes,
          appId.length, delimiter, prettyPrint, sqlDescLength, escapeCSV)
      case None =>
        logWarning(s"Unable to get qualification information for this application")
        ""
    }
  }

  /**
   * Get the summary report for qualification.
   * @param delimiter The delimiter separating fields of the summary report.
   * @param prettyPrint Whether to include the delimiter at start and end and
   *                    add spacing so the data rows align with column headings.
   * @return String containing the summary report.
   */
  def getSummary(delimiter: String = "|", prettyPrint: Boolean = true): String = {
    if (!perSqlOnly) {
      val appInfo = aggregateStats()
      appInfo match {
        case Some(info) =>
          val unSupExecMaxSize = QualOutputWriter.getunSupportedMaxSize(
            Seq(info).map(_.unSupportedExecs.size),
            QualOutputWriter.UNSUPPORTED_EXECS_MAX_SIZE,
            QualOutputWriter.UNSUPPORTED_EXECS.size)
          val unSupExprMaxSize = QualOutputWriter.getunSupportedMaxSize(
            Seq(info).map(_.unSupportedExprs.size),
            QualOutputWriter.UNSUPPORTED_EXPRS_MAX_SIZE,
            QualOutputWriter.UNSUPPORTED_EXPRS.size)
          val hasClusterTags = info.clusterTags.nonEmpty
          val (clusterIdMax, jobIdMax, runNameMax) = if (hasClusterTags) {
            (QualOutputWriter.getMaxSizeForHeader(Seq(info).map(
              _.allClusterTagsMap.getOrElse(QualOutputWriter.CLUSTER_ID, "").size),
              QualOutputWriter.CLUSTER_ID),
              QualOutputWriter.getMaxSizeForHeader(Seq(info).map(
                _.allClusterTagsMap.getOrElse(QualOutputWriter.JOB_ID, "").size),
                QualOutputWriter.JOB_ID),
              QualOutputWriter.getMaxSizeForHeader(Seq(info).map(
                _.allClusterTagsMap.getOrElse(QualOutputWriter.RUN_NAME, "").size),
                QualOutputWriter.RUN_NAME))
          } else {
            (QualOutputWriter.CLUSTER_ID_STR_SIZE, QualOutputWriter.JOB_ID_STR_SIZE,
              QualOutputWriter.RUN_NAME_STR_SIZE)
          }
          val appHeadersAndSizes =
            QualOutputWriter.getSummaryHeaderStringsAndSizes(
              appNameMaxSize = getAppName.length,
              appIdMaxSize = info.appId.length,
              unSupExecMaxSize = unSupExecMaxSize,
              unSupExprMaxSize = unSupExprMaxSize,
              hasClusterTags = hasClusterTags,
              clusterIdMaxSize = clusterIdMax,
              jobIdMaxSize = jobIdMax,
              runNameMaxSize = runNameMax)
          val headerStr = QualOutputWriter.constructOutputRowFromMap(appHeadersAndSizes,
            delimiter, prettyPrint)
          val appInfoStr =
            QualOutputWriter.constructAppSummaryInfo(
              sumInfo = info.estimatedInfo,
              headersAndSizes = appHeadersAndSizes,
              appIdMaxSize = appId.length,
              unSupExecMaxSize = unSupExecMaxSize,
              unSupExprMaxSize = unSupExprMaxSize,
              hasClusterTags = hasClusterTags,
              clusterIdMaxSize = clusterIdMax,
              jobIdMaxSize = jobIdMax,
              runNameMaxSize = runNameMax,
              delimiter = delimiter,
              prettyPrint = prettyPrint)
          headerStr + appInfoStr
        case None =>
          logWarning(s"Unable to get qualification information for this application")
          ""
      }
    } else {
      ""
    }
  }

  /**
   * Get the detailed report for qualification.
   * @param delimiter The delimiter separating fields of the summary report.
   * @param prettyPrint Whether to include the delimiter at start and end and
   *                    add spacing so the data rows align with column headings.
   * @return String containing the detailed report.
   */
  def getDetailed(delimiter: String = "|", prettyPrint: Boolean = true,
      reportReadSchema: Boolean = false): String = {
    if (!perSqlOnly) {
      val appInfo = aggregateStats()
      appInfo match {
        case Some(info) =>
          val headersAndSizesToUse =
            QualOutputWriter.getDetailedHeaderStringsAndSizes(Seq(info), reportReadSchema)
          val headerStr = QualOutputWriter.constructDetailedHeader(headersAndSizesToUse,
            delimiter, prettyPrint)
          val appInfoStr = QualOutputWriter.constructAppDetailedInfo(info, headersAndSizesToUse,
            delimiter, prettyPrint, reportReadSchema)
          headerStr + appInfoStr
        case None =>
          logWarning(s"Unable to get qualification information for this application")
          ""
      }
    } else {
      ""
    }
  }

  // don't aggregate at app level, just sql level
  private def aggregatePerSQLStats(sqlID: Long): Option[EstimatedPerSQLSummaryInfo] = {
    val sqlDesc = sqlIdToInfo.get(sqlID).map(_.description)
    // build the plan graphs to guarantee that the SQL plans were already created
    buildPlanGraphsInternal()
    val origPlanInfo = sqlPlans.get(sqlID).map { plan =>
      SQLPlanParser.parseSQLPlan(appId, plan, sqlID, sqlDesc.getOrElse(""), pluginTypeChecker, this)
    }
    val perSqlInfos = origPlanInfo.flatMap { pInfo =>
      // filter out any execs that should be removed
      val planInfos = removeExecsShouldRemove(Seq(pInfo))
      // get a summary of each SQL Query
      val perSqlStageSummary = summarizeSQLStageInfo(planInfos)
      sqlIdToInfo.get(pInfo.sqlID).map { sqlInfo =>
        val wallClockDur = sqlInfo.duration.getOrElse(0L)
        // get task duration ratio
        val sqlStageSums = perSqlStageSummary.filter(_.sqlID == pInfo.sqlID)
        val estimatedInfo = getPerSQLWallClockSummary(sqlStageSums, wallClockDur, getAppName)
        EstimatedPerSQLSummaryInfo(pInfo.sqlID, sqlInfo.rootExecutionID, pInfo.sqlDesc,
          estimatedInfo)
      }
    }
    perSqlInfos
  }

  override def buildPlanGraphs(): Unit = {
    // Do nothing because the events are not parsed yet by the time
    // this function is called.
  }

  /**
   * Whether the plan graphs have already been built.
   */
  private var _graphBuilt = false

  /**
   * Build the plan graphs for all the SQL Plans if any.
   * It is possible that this method gets called multiple times. For example, aggregateStats
   * is called by different methods in teh same object and we want to make sure that it does not
   * create the graphs multiple times.
   */
  def buildPlanGraphsInternal(): Unit = {
    this.synchronized {
      if (!_graphBuilt) {
        sqlManager.buildPlanGraph(this)
        _graphBuilt = true
      }
    }
  }

  override def cleanupSQL(sqlID: Long): Unit = {
    super.cleanupSQL(sqlID)
    // reset the graph built flag because the sqlIds were removed.
    this.synchronized {
      _graphBuilt = false
    }
  }
  /**
   * Aggregate and process the application after reading the events.
   * @return Option of QualificationSummaryInfo, Some if we were able to process the application
   *         otherwise None.
   */
  override def aggregateStats(): Option[QualificationSummaryInfo] = {
    // build the plan graphs to guarantee that the SQL plans were already created
    buildPlanGraphsInternal()
    // make sure that the APPSQLAppAnalyzer has processed the running application
    AppSQLPlanAnalyzer(this)
    super.aggregateStats()
  }
}
