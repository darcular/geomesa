/***********************************************************************
* Copyright (c) 2013-2016 Commonwealth Computer Research, Inc.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0
* which accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*************************************************************************/

package org.locationtech.geomesa.kafka

import com.google.common.base.Ticker
import com.vividsolutions.jts.geom.Point
import org.geotools.factory.CommonFactoryFinder
import org.geotools.feature.simple.SimpleFeatureBuilder
import org.geotools.filter.text.ecql.ECQL
import org.geotools.filter.visitor.SimplifyingFilterVisitor
import org.joda.time.{DateTime, DateTimeZone, Instant}
import org.locationtech.geomesa.utils.geotools.Conversions._
import org.locationtech.geomesa.filter._
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.text.WKTUtils
import org.opengis.feature.simple.SimpleFeature
import org.opengis.filter.spatial._
import org.opengis.filter.temporal._
import org.opengis.filter._
import scala.language._


import scala.util.Random

class AttributeIndexingTest {
  implicit def sfToCreate(feature: SimpleFeature): CreateOrUpdate = CreateOrUpdate(Instant.now, feature)
//
//  implicit val ff = CommonFactoryFinder.getFilterFactory2

  val spec = "Who:String:index=full,What:Int,When:Date,*Where:Point:srid=4326,Why:String"
  val MIN_DATE = new DateTime(2014, 1, 1, 0, 0, 0, DateTimeZone.forID("UTC"))
  val seconds_per_year = 365L * 24L * 60L * 60L
  val string = "foo"

  def randDate = MIN_DATE.plusSeconds( scala.math.round(scala.util.Random.nextFloat * seconds_per_year)).toDate

  val sft = SimpleFeatureTypes.createType("test", spec)
  val builder = new SimpleFeatureBuilder(sft)

  val names = Array("Addams", "Bierce", "Clemens", "Damon", "Evan", "Fred", "Goliath", "Harry")
  def getName: String = names(Random.nextInt(names.length))

  def getPoint: Point = {
    val minx = -180
    val miny = -90
    val dx = 360
    val dy = 180

    val x = minx + Random.nextDouble * dx
    val y = miny + Random.nextDouble * dy
    WKTUtils.read(s"POINT($x $y)").asInstanceOf[Point]
  }

  def buildFeature(i: Int): SimpleFeature = {
    builder.set("Who", getName)
    builder.set("What", Random.nextInt(10))
    builder.set("When", randDate)
    builder.set("Where", getPoint)
    if (Random.nextBoolean()) { builder.set("Why", string) }
    builder.buildFeature(i.toString)
  }


  def time[A](a: => A) = {
    val now = System.currentTimeMillis()
    val result = a
    //println("%f seconds".format( (System.nanoTime - now) / 1000000000.0 ))

    (result, System.currentTimeMillis() - now)
  }

  implicit val ticker = Ticker.systemTicker()
  val lfc = new LiveFeatureCache(sft, None)

  val ab = ECQL.toFilter("Who IN('Addams', 'Bierce')")
  val cd = ECQL.toFilter("Who IN('Clemens', 'Damon')")

  val w14 = ECQL.toFilter("What = 1 OR What = 2 OR What = 3 or What = 4")

  val where = ECQL.toFilter("BBOX(Where, 0, 0, 180, 90)")
  val where2 = ECQL.toFilter("BBOX(Where, -180, -90, 0, 0)")

  val bbox2 = ff.or(where, where2)

  val justified = ECQL.toFilter("Why is not null")

  val justifiedAB = ff.and(ff.and(ab, w14), justified)
  val justifiedCD = ff.and(ff.and(cd, w14), justified)

  val just = ff.or(justifiedAB, justifiedCD)

  val justBBOX = ff.and(just, where)
  val justBBOX2 = ff.and(just, where2)

  val justOR = ff.or(justBBOX, justBBOX2)

  val niceAnd = ff.and(justOR, just)

  val geoJustAB = ff.and(justifiedAB, bbox2)
  val geoJustCD = ff.and(justifiedCD, bbox2)
  val badOr = ff.or(geoJustAB, geoJustCD)


  val filters = Seq(ab, cd, w14, where, justified, justifiedAB, justifiedCD, just, justBBOX, justBBOX2, bbox2)

  val feats = (0 until 100000).map(buildFeature)
  feats.foreach{lfc.createOrUpdateFeature(_)}

  val sfv = new SimplifyingFilterVisitor

  time(lfc.getReaderForFilter(where.accept(sfv, null).asInstanceOf[Filter]).getIterator.size)


  def benchmark(f: Filter) {
    val (regularCount, t1) = time(lfc.getReaderForFilter(f).getIterator.size)


    val cnf = rewriteFilterInCNF(f)
    val (cnfCount, tcnf) = time(lfc.getReaderForFilter(cnf.accept(sfv, null).asInstanceOf[Filter]).getIterator.size)

    val dnf = rewriteFilterInDNF(f)
    val (dnfCount, tdnf) = time(lfc.getReaderForFilter(dnf.accept(sfv, null).asInstanceOf[Filter]).getIterator.size)

    //val (simpleCount, t2) = time(lfc.getReaderForFilter(f.accept(sfv, null).asInstanceOf[Filter]).getIterator.size)
    val (unoptimizedCount, t3) = time(lfc.unoptimized(f).getIterator.size)

    println(s"\nFilter: $f")
    if (regularCount == cnfCount && regularCount == dnfCount && regularCount == unoptimizedCount) {
      if (t3 < t1) {
        println("'Unoptimized' was quicker than regular")
      }
      if (tcnf < t1) {
        println("'CNF' was quicker than regular")
      }
      if (tdnf < t1) {
        println("'DNF' was quicker than regular")
      }
      println(s"All filters returned $regularCount")

    } else {
      println(s"MISMATCHED Counts: Regular: $regularCount CNF: $tcnf DNF: $tdnf  Unoptimized: $unoptimizedCount")
    }
    println(s"Timings: regular: $t1 CNF: $tcnf DNF: $tdnf unoptimized: $t3\n")
  }

}

import scala.collection.JavaConversions._

class GraphVizFilterVisitor extends FilterVisitor {
  override def visit(filter: And, extraData: scala.Any): AnyRef = {
    val count = extraDataToCount(extraData)
    
    printOperand(filter, "AND", count)
    //filter.getChildren.foreach(_.accept(this, count + 1))
    linkChildren(filter, count)
  }

  override def visit(filter: Or, extraData: scala.Any): AnyRef = {
    val count = extraDataToCount(extraData)
    
    printOperand(filter, "OR", count)
    //filter.getChildren.foreach(_.accept(this, count + 1))
    linkChildren(filter, count)
  }

  def linkChildren(binary: BinaryLogicOperator, count: Int) = {
    binary.getChildren.foreach { c =>
      val childRand = math.abs(scala.util.Random.nextInt())
      println(s"node_${nodeName(binary, count)} -> node_${nodeName(c, childRand)}")
      c.accept(this, childRand)
    }
    null
  }

  def printOperand(filter: Filter, op: String, count: Int) = {
    println(s"""node_${nodeName(filter, count)} [ label="$op" shape="rectangle"]""")

  }

  override def visit(filter: Not, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(metBy: MetBy, extraData: scala.Any): AnyRef = printRectangle(metBy, extraData)
  override def visit(meets: Meets, extraData: scala.Any): AnyRef = printRectangle(meets, extraData)
  override def visit(ends: Ends, extraData: scala.Any): AnyRef = printRectangle(ends, extraData)
  override def visit(endedBy: EndedBy, extraData: scala.Any): AnyRef = printRectangle(endedBy, extraData)
  override def visit(begunBy: BegunBy, extraData: scala.Any): AnyRef = printRectangle(begunBy, extraData)
  override def visit(begins: Begins, extraData: scala.Any): AnyRef = printRectangle(begins, extraData)
  override def visit(anyInteracts: AnyInteracts, extraData: scala.Any): AnyRef = printRectangle(anyInteracts, extraData)

  override def visit(contains: TOverlaps, extraData: scala.Any): AnyRef = printRectangle(contains, extraData)
  override def visit(equals: TEquals, extraData: scala.Any): AnyRef = printRectangle(equals, extraData)
  override def visit(contains: TContains, extraData: scala.Any): AnyRef = printRectangle(contains, extraData)
  override def visit(overlappedBy: OverlappedBy, extraData: scala.Any): AnyRef = printRectangle(overlappedBy, extraData)
  override def visit(filter: Touches, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: Overlaps, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(during: During, extraData: scala.Any): AnyRef = printRectangle(during, extraData)
  override def visit(before: Before, extraData: scala.Any): AnyRef = printRectangle(before, extraData)
  override def visit(after: After, extraData: scala.Any): AnyRef = printRectangle(after, extraData)
  override def visit(filter: Id, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: Equals, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: IncludeFilter, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: DWithin, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: Within, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: PropertyIsLessThanOrEqualTo, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: PropertyIsLessThan, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: PropertyIsGreaterThanOrEqualTo, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: PropertyIsGreaterThan, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: PropertyIsNotEqualTo, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: PropertyIsEqualTo, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  override def visit(filter: PropertyIsBetween, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: ExcludeFilter, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: PropertyIsLike, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: PropertyIsNull, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: PropertyIsNil, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: BBOX, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: Beyond, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: Contains, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: Crosses, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visit(filter: Disjoint, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)

  override def visitNullFilter(extraData: scala.Any): AnyRef = ??? // printRectangle(filter, extraData)

  override def visit(filter: Intersects, extraData: scala.Any): AnyRef = printRectangle(filter, extraData)
  
  def printRectangle(filter: Filter, extraData: scala.Any) = {
    val count = extraDataToCount(extraData)
    println(s"""node_${nodeName(filter, count)} [ label="${filter.toString}" shape="rectangle"]""")
    null
  }

  def extraDataToCount(extraData: scala.Any): Int = {
    extraData match {
      case i: Int => i
      case _ => 0
    }
  }
  
  def nodeName(filter: Filter, count: Int): String = {
    s"${count}_${filter.hashCode().toLong.toHexString}"
  }
}