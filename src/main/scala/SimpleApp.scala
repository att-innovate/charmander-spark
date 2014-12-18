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


// ../spark/bin/spark-submit --class "SimpleApp --master local[*]  target/scala-2.10/simple-project_2.10-1.0.jar

import scala.collection.mutable.SynchronizedQueue
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.streaming._
import org.json4s.jackson.JsonMethods
import java.io._
import java.net._

class ScalaCustomException(msg: String) extends RuntimeException(msg)

case class MemoryUsage(timestamp: BigDecimal, memory: BigDecimal)

object SimpleApp {

  val REDIS_HOST = "172.31.2.11"
  val REDIS_PORT = 31600
  val INFLUXDB_HOST = "172.31.2.11"
  val INFLUXDB_PORT = 31410
  val UNIQUE_LOAD = 75

  def setToRedis(key: String, value: String): Unit = {
    val socket = new Socket(REDIS_HOST, REDIS_PORT)
    var out = new PrintWriter(socket.getOutputStream(), true)
    var in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
    out.println("*3\r\n$3\r\nSET\r\n$" + key.length.toString + "\r\n" + key + "\r\n$" + value.length.toString + "\r\n" + value + "\r\n")
    if (in.readLine() != "+OK")
      throw new ScalaCustomException("Could not set value in Redis.")
  }

  def getFromRedis(key: String): String = {
    val socket = new Socket(REDIS_HOST, REDIS_PORT)
    var out = new PrintWriter(socket.getOutputStream(), true)
    var in = new BufferedReader(new InputStreamReader(socket.getInputStream()))
    out.println("*2\r\n$3\r\nGET\r\n$" + key.length.toString + "\r\n" + key + "\r\n")
    if (in.readLine().charAt(1) == '-') //Redis responses with $-1 if no value found
      return ""
    else
      return in.readLine()
  }

  def sendQueryStringToOpenInfluxDB(query: String): String = {
    val in = scala.io.Source.fromURL("http://"
      + INFLUXDB_HOST
      + ":"
      + INFLUXDB_PORT
      + "/db/charmander/series?u=root&p=root&q="
      + java.net.URLEncoder.encode(query),
      "utf-8")
    var data = ""
    for (line <- in.getLines)
      data = line
    return data
  }

  def main(args: Array[String]) {

    // Create the contexts
    val sc = new SparkContext()
    val ssc = new StreamingContext(sc, Seconds(5))
    val sqlContext = new org.apache.spark.sql.SQLContext(sc)
    import sqlContext._

    // Create the queue through which RDDs can be pushed to
    // a QueueInputDStream
    val rddQueue = new SynchronizedQueue[RDD[List[List[BigDecimal]]]]()

    var globalMax: BigDecimal = 0

    // Create the QueueInputDStream and use it do some processing
    val inputStream = ssc.queueStream(rddQueue)
    inputStream.foreachRDD(rdd => {
      val memoryusage = rdd.map(p => MemoryUsage(BigDecimal(p(0).asInstanceOf[BigInt]), BigDecimal(p(2).asInstanceOf[BigInt])))
      memoryusage.registerTempTable("memoryusage")
      val newestMax = sqlContext.sql("select max(memory) from memoryusage").first()
      println(newestMax)
      globalMax = BigDecimal(newestMax(0).toString)

      val maxMemUse = getFromRedis("SPARK_MAX")
      if (maxMemUse != "") {
        //application exists in redis
        if (BigDecimal(maxMemUse) < globalMax) {
          //only set if current max > all-time max
          setToRedis("SPARK_ADJUSTED", (globalMax * 1.1).toInt.toString)
          setToRedis("SPARK_MAX", globalMax.toString)
        }
      } else {
        //application does not exist in redis
        setToRedis("SPARK_MAX", globalMax.toString)
        if (globalMax > 0) setToRedis("SPARK_ADJUSTED", (globalMax * 1.1).toInt.toString)
      }
    })
    ssc.start()


    while (true) {
      val rawData = sendQueryStringToOpenInfluxDB("select memory_usage from stats limit 10")
      val json = JsonMethods.parse(rawData)
      val points = json \\ "points"
      val mypoints = points.values
      val rdd = sc.parallelize(mypoints.asInstanceOf[List[List[BigDecimal]]])
      rddQueue += rdd

      Thread.sleep(5000)
    }

  }
}
