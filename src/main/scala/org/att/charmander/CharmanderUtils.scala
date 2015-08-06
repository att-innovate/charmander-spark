// The MIT License (MIT)
//
// Copyright (c) 2014 AT&T
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.

package org.att.charmander

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD

import java.io.{BufferedReader, IOException, InputStreamReader, PrintWriter, File}
import java.net.Socket

import org.json4s.jackson.JsonMethods

import scala.collection.mutable


class CharmanderException(msg: String) extends RuntimeException(msg)

trait CharmanderUtils {
  def getMeteredTaskNamesFromRedis: List[String]

  // v1, cAdvisor only
  def getRDDForTask(sc: SparkContext, taskName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]]
  def getRDDForNode(sc: SparkContext, nodeName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]]

  // v2
  def getRDDForTask(sc: SparkContext, databaseName: String, taskName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]]
  def getRDDForNode(sc: SparkContext, databaseName: String, nodeName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]]
  def getRDDForNetwork(sc: SparkContext, databaseName: String, nodeName: String, networkInterface: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]]

  def getRDDAndColumnsForQuery(sc: SparkContext, databaseName: String, sqlQuery: String): (List[String], RDD[List[BigDecimal]])

  def setTaskIntelligence(taskName: String, attributeName: String, value: String)
  def getTaskIntelligence(taskName: String, attributeName: String): String

  def exportDataToCSV(databaseName: String, sqlQuery: String, fileName: String)

  // internal
  def setInRedis(key: String, value: String)
  def getFromRedis(key: String): String
  def getRDDForQuery(sc: SparkContext, databaseName: String, sqlQuery: String): (List[String], RDD[List[BigDecimal]])
  def sendQueryToInfluxDB(databaseName: String, query: String): String
}


object CharmanderUtils {

  val REDIS_HOST = "172.31.2.11"
  val REDIS_PORT = 31600
  val INFLUXDB_HOST = "172.31.2.11"
  val INFLUXDB_PORT = 31410

  val CADVISOR_DB = "Charmander"
  val VECTOR_DB = "charmander-dc"


  def getMeteredTaskNamesFromRedis: List[String] = try {
    var tasks = mutable.Set[String]()
    val socket = new Socket(REDIS_HOST, REDIS_PORT)
    var out = new PrintWriter(socket.getOutputStream, true)
    var in = new BufferedReader(new InputStreamReader(socket.getInputStream))
    out.println("*2\r\n$4\r\nKEYS\r\n$26\r\ncharmander:tasks-metered:*\r\n")
    val numberOfResultsRaw: String = in.readLine()
    if (numberOfResultsRaw == "*0") {
      return tasks.toList
    }

    val numberOfResults = numberOfResultsRaw.substring(1).toInt
    for (i <- 1 to numberOfResults) {
      in.readLine() // we don't care about the length
      val taskNameRaw = in.readLine()
      val taskName = taskNameRaw slice((taskNameRaw lastIndexOf ':') + 1, taskNameRaw lastIndexOf '-')
      tasks += taskName
    }

    tasks.toList

  } catch {
    case e: java.net.ConnectException => List[String]()
  }


  def getRDDForTask(sc: SparkContext, taskName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]] = {
    getRDDForTask(sc, CADVISOR_DB, taskName, attributeName, numberOfPoints)
  }

  def getRDDForTask(sc: SparkContext, databaseName: String, taskName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]] = {
    val sqlQuery = "select %s from stats where container_name =~ /%s*/ limit %d".format(attributeName, taskName, numberOfPoints)
    val (_, result) = getRDDForQuery(sc, databaseName, sqlQuery)
    result.setName(taskName)
    result
  }

  def getRDDForNode(sc: SparkContext, nodeName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]] = {
    getRDDForNode(sc, CADVISOR_DB, nodeName, attributeName, numberOfPoints)
  }

  def getRDDForNode(sc: SparkContext, databaseName: String, nodeName: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]] = {
    val sqlQuery = "select %s from machine where hostname = '%s' limit %d".format(attributeName, nodeName, numberOfPoints)
    val (_, result) = getRDDForQuery(sc, databaseName, sqlQuery)
    result.setName(nodeName)
    result
  }

  def getRDDForNetwork(sc: SparkContext, databaseName: String, nodeName: String, networkInterface: String, attributeName: String, numberOfPoints: Int): RDD[List[BigDecimal]] = {
    val sqlQuery = "select %s from network where hostname = '%s' and interface_name = '%s' limit %d".format(attributeName, nodeName, networkInterface, numberOfPoints)
    val (_, result) = getRDDForQuery(sc, databaseName, sqlQuery)
    result.setName(networkInterface)
    result
  }

  def getRDDAndColumnsForQuery(sc: SparkContext, databaseName: String, sqlQuery: String): (List[String], RDD[List[BigDecimal]]) = {
    getRDDForQuery(sc, databaseName, sqlQuery)
  }



  def setTaskIntelligence(taskName: String, attributeName: String, value: String) = {
    val redisKey = "charmander:task-intelligence:" + taskName + ":"+attributeName
    setInRedis(redisKey, value)
  }

  def getTaskIntelligence(taskName: String, attributeName: String): String = {
    val redisKey = "charmander:task-intelligence:" + taskName + ":"+attributeName
    getFromRedis(redisKey)
  }


  def exportDataToCSV(databaseName: String, sqlQuery: String, fileName: String) = {
    val writer = new PrintWriter(new File(fileName))

    val rawData = CharmanderUtils.sendQueryToInfluxDB(databaseName,sqlQuery)
    if (rawData.length != 0) {
      val json = JsonMethods.parse(rawData)
      val columns = (json \\ "columns").values
      val points  = (json \\ "points").values

      // handle header
      var header = ""
      for (columnName <- columns.asInstanceOf[List[String]]) {
        if (header != "") header = header + ","

        header = header + columnName
      }

      writer.println(header)

      for (point <- points.asInstanceOf[List[Any]]) {
        var line = ""
        for (elmt <- point.asInstanceOf[List[Any]]) {
          if (line != "") line = line + ","

          line = line + elmt.toString
        }
        writer.println(line)
      }

    }

    writer.close()
  }


  // internal
  def setInRedis(key: String, value: String) = {
    val socket = new Socket(REDIS_HOST, REDIS_PORT)
    var out = new PrintWriter(socket.getOutputStream, true)
    var in = new BufferedReader(new InputStreamReader(socket.getInputStream))
    out.println("*3\r\n$3\r\nSET\r\n$" + key.length.toString + "\r\n" + key + "\r\n$" + value.length.toString + "\r\n" + value + "\r\n")
    if (in.readLine() != "+OK")
      throw new CharmanderException("Could not set value in Redis.")
  }

  def getFromRedis(key: String): String = {
    val socket = new Socket(REDIS_HOST, REDIS_PORT)
    var out = new PrintWriter(socket.getOutputStream(), true)
    var in = new BufferedReader(new InputStreamReader(socket.getInputStream))
    out.println("*2\r\n$3\r\nGET\r\n$" + key.length.toString + "\r\n" + key + "\r\n")
    if (in.readLine().charAt(1) == '-') return "" //Redis responses with $-1 if no value found

    in.readLine()
  }

  def getRDDForQuery(sc: SparkContext, databaseName: String, sqlQuery: String): (List[String], RDD[List[BigDecimal]]) = {
    val rawData = CharmanderUtils.sendQueryToInfluxDB(databaseName,sqlQuery)
    if (rawData.length == 0) return (List.empty[String], sc.emptyRDD)

    val json = JsonMethods.parse(rawData)

    val columns = (json \\ "columns").values
    val points  = (json \\ "points").values

    if (points.isInstanceOf[List[Any]] == false) return (List.empty[String], sc.emptyRDD)

    (columns.asInstanceOf[List[String]], sc.parallelize(points.asInstanceOf[List[List[BigDecimal]]]))
  }

  def sendQueryToInfluxDB(databaseName: String, query: String): String = try {
    val in = scala.io.Source.fromURL("http://"
      + INFLUXDB_HOST
      + ":"
      + INFLUXDB_PORT
      + "/db/"+databaseName+"/series?u=root&p=root&q="
      + java.net.URLEncoder.encode(query),
      "utf-8")
    var data = ""
    for (line <- in.getLines)
      data = line
    data

  } catch {
    case e: IOException => ""
    case e: java.net.ConnectException => ""
  }


}
