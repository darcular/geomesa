/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.accumulo.index

import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.GeometryCollection
import org.apache.accumulo.core.client.IteratorSetting
import org.apache.accumulo.core.iterators.user.RegExFilter
import org.apache.hadoop.io.Text
import org.geotools.factory.Hints
import org.geotools.filter.text.ecql.ECQL
import org.joda.time.{DateTime, Interval}
import org.locationtech.geomesa.accumulo.GEOMESA_ITERATORS_IS_DENSITY_TYPE
import org.locationtech.geomesa.accumulo.data.stats.GeoMesaStats
import org.locationtech.geomesa.accumulo.data.tables.SpatioTemporalTable
import org.locationtech.geomesa.accumulo.index.QueryHints._
import org.locationtech.geomesa.accumulo.index.QueryPlanner._
import org.locationtech.geomesa.accumulo.index.Strategy._
import org.locationtech.geomesa.accumulo.iterators._
import org.locationtech.geomesa.features.SerializationType
import org.locationtech.geomesa.features.SerializationType.SerializationType
import org.locationtech.geomesa.filter.FilterHelper._
import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType
import org.locationtech.geomesa.utils.geotools._
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.filter.Filter

@deprecated("z2")
class STIdxStrategy(val filter: QueryFilter) extends Strategy with LazyLogging with IndexFilterHelpers {

  override def getQueryPlan(queryPlanner: QueryPlanner, hints: Hints, output: ExplainerOutputType) = {
    val acc             = queryPlanner.ds
    val sft             = queryPlanner.sft
    val version         = sft.getSchemaVersion
    val schema          = Option(sft.getStIndexSchema).getOrElse("")
    val featureEncoding = queryPlanner.ds.getFeatureEncoding(sft)
    val keyPlanner      = IndexSchema.buildKeyPlanner(schema)
    val cfPlanner       = IndexSchema.buildColumnFamilyPlanner(schema)

    output(s"Scanning ST index table for feature type ${sft.getTypeName}")
    output(s"Filter: ${filter.primary} ${filter.secondary.map(_.toString).getOrElse("")}")

    if (filter.primary.isEmpty) {
      logger.warn(s"Querying Accumulo without SpatioTemporal filter.")
    }

    val dtgField = sft.getDtgField

    // standardize the two key query arguments:  polygon and date-range

    // convert the list of OR'd geometries coming back into a single geometry or geometry collection
    val geometryToCover = filter.primary.map(extractGeometries(_, sft.getGeomField, sft.isPoints)).flatMap {
      case g if g.length < 2 => g.headOption
      case g => Some(new GeometryCollection(g.toArray, g.head.getFactory))
    }.getOrElse(WholeWorldPolygon)

    val interval = {
      val intervals = for { dtg <- dtgField; filter <- filter.primary } yield { extractIntervals(filter, dtg) }
      // get the outer bounds for the intervals
      val reduced = intervals.getOrElse(Seq.empty).reduceLeftOption[(DateTime, DateTime)] {
        case ((startLeft, endLeft), (startRight, endRight)) =>
          val start = if (startLeft.isAfter(startRight)) startRight else startLeft
          val end = if (endLeft.isBefore(endRight)) endRight else endLeft
          (start, end)
      }
      reduced.map { case (start, end) => new Interval(start, end) }.orNull
    }

    output(s"Geometry to cover: $geometryToCover")
    output(s"Interval to cover: $interval")

    val keyPlanningFilter = buildFilter(geometryToCover, interval)

    val oint  = IndexSchema.somewhen(interval)

    output(s"STII Filter: ${filter.primary.getOrElse("No STII Filter")}")
    output(s"Interval:  ${oint.getOrElse("No interval")}")
    output(s"Filter: ${Option(keyPlanningFilter).getOrElse("No Filter")}")

    val (iterators, kvsToFeatures, useIndexEntries, hasDupes) = if (hints.isDensityQuery) {
      val (width, height) = hints.getDensityBounds.get
      val envelope = hints.getDensityEnvelope.get
      val weight = hints.getDensityWeight
      val p = iteratorPriority_AnalysisIterator
      val iter = DensityIterator.configure(sft, SpatioTemporalTable, featureEncoding, schema,
        filter.filter, envelope, width, height, weight, p)
      (Seq(iter), KryoLazyDensityIterator.kvsToFeatures(), false, false)
    } else if (featureEncoding == SerializationType.KRYO &&
        // we have some special handling for bin line dates not implemented in the bin iter yet
        !(sft.isLines && hints.isBinQuery)) {
      // TODO GEOMESA-822 add bin line dates to distributed bin aggregation
      if (hints.isBinQuery) {
        // use the server side aggregation
        val iter = BinAggregatingIterator.configureDynamic(sft, SpatioTemporalTable, filter.filter, hints, sft.nonPoints)
        (Seq(iter), BinAggregatingIterator.kvsToFeatures(), false, false)
      } else if (hints.isStatsIteratorQuery) {
        val iter = KryoLazyStatsIterator.configure(sft, SpatioTemporalTable, filter.filter, hints, sft.nonPoints)
        (Seq(iter), KryoLazyStatsIterator.kvsToFeatures(sft), false, false)
      } else {
        val iters = KryoLazyFilterTransformIterator.configure(sft, filter.filter, hints).toSeq
        (iters, queryPlanner.kvsToFeatures(sft, hints.getReturnSft, SpatioTemporalTable), false, sft.nonPoints)
      }
    } else {
      // legacy iterators
      val ecql = filter.secondary
      val iteratorConfig = IteratorTrigger.chooseIterator(filter.filter.getOrElse(Filter.INCLUDE), ecql, hints, sft)
      val stiiIterCfg = getSTIIIterCfg(iteratorConfig, hints, sft, filter.primary, ecql, featureEncoding, version)
      val aggIterCfg = configureAggregatingIterator(hints, geometryToCover, schema, featureEncoding, sft)

      val indexEntries = iteratorConfig.iterator match {
        case IndexOnlyIterator      => true
        case SpatioTemporalIterator => false
      }
      val iters = Seq(stiiIterCfg) ++ aggIterCfg
      val kvs = if (hints.isBinQuery) {
        BinAggregatingIterator.nonAggregatedKvsToFeatures(sft, SpatioTemporalTable, hints, featureEncoding)
      } else {
        queryPlanner.kvsToFeatures(sft, hints.getReturnSft, SpatioTemporalTable)
      }
      (iters, kvs, indexEntries, sft.nonPoints)
    }

    // set up row ranges and regular expression filter
    val qp = planQuery(filter, keyPlanningFilter, useIndexEntries, output, keyPlanner, cfPlanner)

    val table = acc.getTableName(sft.getTypeName, SpatioTemporalTable)
    val numThreads = acc.getSuggestedThreads(sft.getTypeName, SpatioTemporalTable)
    qp.copy(table = table, iterators = iterators, kvsToFeatures = kvsToFeatures,
      numThreads = numThreads, hasDuplicates = hasDupes)
  }

  private def getSTIIIterCfg(iteratorConfig: IteratorConfig,
                     hints: Hints,
                     featureType: SimpleFeatureType,
                     stFilter: Option[Filter],
                     ecqlFilter: Option[Filter],
                     featureEncoding: SerializationType,
                     version: Int): IteratorSetting = {
    iteratorConfig.iterator match {
      case IndexOnlyIterator =>
        configureIndexIterator(featureType, hints, featureEncoding, stFilter,
          iteratorConfig.transformCoversFilter, version)
      case SpatioTemporalIterator =>
        configureSpatioTemporalIntersectingIterator(featureType, hints, featureEncoding, stFilter,
          ecqlFilter, hints.isDensityQuery)
    }
  }

  // establishes the regular expression that defines (minimally) acceptable rows
  def configureRowRegexIterator(regex: String): IteratorSetting = {
    val name = "regexRow-" + randomPrintableString(5)
    val cfg = new IteratorSetting(iteratorPriority_RowRegex, name, classOf[RegExFilter])
    RegExFilter.setRegexs(cfg, regex, null, null, null, false)
    cfg
  }

  // returns an iterator over [key,value] pairs where the key is taken from the index row and the value is a SimpleFeature,
  // which is either read directory from the data row  value or generated from the encoded index row value
  // -- for items that either:
  // 1) the GeoHash-box intersects the query polygon; this is a coarse-grained filter
  // 2) the DateTime intersects the query interval; this is a coarse-grained filter
  def configureIndexIterator(
      featureType: SimpleFeatureType,
      hints: Hints,
      featureEncoding: SerializationType,
      filter: Option[Filter],
      transformsCoverFilter: Boolean,
      version: Int): IteratorSetting = {

    val cfg = new IteratorSetting(iteratorPriority_SpatioTemporalIterator,
      "within-" + randomPrintableString(5),classOf[IndexIterator])

    configureStFilter(cfg, filter)

    configureVersion(cfg, version)
    if (transformsCoverFilter) {
      // apply the transform directly to the index iterator
      hints.getTransformSchema.foreach(testType => configureFeatureType(cfg, testType))
    } else {
      // we need to evaluate the original feature before transforming
      // transforms are applied afterwards
      configureFeatureType(cfg, featureType)
      configureTransforms(cfg, hints)
    }
    configureIndexValues(cfg, featureType)
    configureFeatureEncoding(cfg, featureEncoding)
    cfg
  }

  // returns only the data entries -- no index entries -- for items that either:
  // 1) the GeoHash-box intersects the query polygon; this is a coarse-grained filter
  // 2) the DateTime intersects the query interval; this is a coarse-grained filter
  def configureSpatioTemporalIntersectingIterator(
      featureType: SimpleFeatureType,
      hints: Hints,
      featureEncoding: SerializationType,
      stFilter: Option[Filter],
      ecqlFilter: Option[Filter],
      isDensity: Boolean): IteratorSetting = {
    val cfg = new IteratorSetting(iteratorPriority_SpatioTemporalIterator,
      "within-" + randomPrintableString(5),
      classOf[SpatioTemporalIntersectingIterator])
    val combinedFilter = (stFilter, ecqlFilter) match {
      case (Some(st), Some(ecql)) => filterListAsAnd(Seq(st, ecql))
      case (Some(_), None)        => stFilter
      case (None, Some(_))        => ecqlFilter
      case (None, None)           => None
    }
    configureFeatureType(cfg, featureType)
    configureFeatureEncoding(cfg, featureEncoding)
    configureTransforms(cfg, hints)
    configureEcqlFilter(cfg, combinedFilter.map(ECQL.toCQL))
    if (isDensity) {
      cfg.addOption(GEOMESA_ITERATORS_IS_DENSITY_TYPE, "isDensity")
    }
    cfg
  }

  def planQuery(qf: QueryFilter,
                filter: KeyPlanningFilter,
                useIndexEntries: Boolean,
                output: ExplainerOutputType,
                keyPlanner: KeyPlanner,
                cfPlanner: ColumnFamilyPlanner): BatchScanPlan = {
    output(s"Planning query")

    val keyPlan = keyPlanner.getKeyPlan(filter, useIndexEntries, output)

    val columnFamilies = cfPlanner.getColumnFamiliesToFetch(filter)

    // always try to use range(s) to remove easy false-positives
    val accRanges: Seq[org.apache.accumulo.core.data.Range] = keyPlan match {
      case KeyRanges(ranges) => ranges.map(r => new org.apache.accumulo.core.data.Range(r.start, r.end))
      case _ => Seq(new org.apache.accumulo.core.data.Range())
    }

    // if you have a list of distinct column-family entries, fetch them
    val cf = columnFamilies match {
      case KeyList(keys) => keys.map { cf => new Text(cf) }
      case _             => Seq()
    }

    // partially fill in, rest will be filled in later
    BatchScanPlan(qf, null, accRanges, null, cf, null, -1, hasDuplicates = false)
  }
}

@deprecated("z2")
object STIdxStrategy extends StrategyProvider {

  override protected def statsBasedCost(sft: SimpleFeatureType,
                                        filter: QueryFilter,
                                        transform: Option[SimpleFeatureType],
                                        stats: GeoMesaStats): Option[Long] = {
    filter.primary match {
      case Some(f) => stats.getCount(sft, f, exact = false)
      case None    => Some(Long.MaxValue)
    }
  }

  // slots in between high- and low-cardinality attributes
  override protected def indexBasedCost(sft: SimpleFeatureType,
                                        filter: QueryFilter,
                                        transform: Option[SimpleFeatureType]): Long = 400L
}
