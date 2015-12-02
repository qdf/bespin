package io.bespin.scala.mapreduce.wordcount;

import io.bespin.scala.util.Tokenizer
import io.bespin.scala.util.WritableConversions

import java.util.StringTokenizer

import scala.collection.JavaConversions._
import scala.collection.mutable._

import org.apache.hadoop.conf._
import org.apache.hadoop.fs._
import org.apache.hadoop.io._
import org.apache.hadoop.mapreduce._
import org.apache.hadoop.mapreduce.lib.input._
import org.apache.hadoop.mapreduce.lib.output._
import org.apache.hadoop.util.Tool
import org.apache.hadoop.util.ToolRunner
import org.apache.log4j._
import org.rogach.scallop._

class Conf(args: Seq[String]) extends ScallopConf(args) {
  mainOptions = Seq(input, output, reducers)
  val input = opt[String](descr = "input path", required = true)
  val output = opt[String](descr = "output path", required = true)
  val reducers = opt[Int](descr = "number of reducers", required = false, default = Some(1))
  val imc = opt[Boolean](descr = "use in-mapper combining", required = false)
}

object WordCount extends Configured with Tool with WritableConversions with Tokenizer {
  val log = Logger.getLogger(getClass().getName());

  class MyMapper extends Mapper[LongWritable, Text, Text, IntWritable] {
    override def map(key: LongWritable, value: Text, context: Mapper[LongWritable, Text, Text, IntWritable]#Context) = {
      tokenize(value).foreach(word => context.write(word, 1))
    }
  }

  class MyMapperIMC extends Mapper[LongWritable, Text, Text, IntWritable] {
    val counts = new HashMap[String, Int]() { override def default(key: String) = 0 }

    override def map(key: LongWritable, value: Text, context: Mapper[LongWritable, Text, Text, IntWritable]#Context) = {
      tokenize(value.toString).foreach(word => counts.put(word, counts(word) + 1))
    }

    override def cleanup(context: Mapper[LongWritable, Text, Text, IntWritable]#Context) = {
      counts.foreach({ case (k, v) => context.write(k, v) })
    }
  }

  class MyReducer extends Reducer[Text, IntWritable, Text, IntWritable] {
    override def reduce(key: Text, values: java.lang.Iterable[IntWritable], context: Reducer[Text, IntWritable, Text, IntWritable]#Context) = {
      context.write(key, values.reduceLeft((a, b) => a + b));
    }
  }

  override def run(argv: Array[String]) : Int = {
    val args = new Conf(argv)

    log.info("Input: " + args.input())
    log.info("Output: " + args.output())
    log.info("Number of reducers: " + args.reducers())
    log.info("Use in-mapper combining: " + args.imc())

    val conf = getConf();
    val job = Job.getInstance(conf);

    FileInputFormat.addInputPath(job, new Path(args.input()))
    FileOutputFormat.setOutputPath(job, new Path(args.output()))

    job.setMapOutputKeyClass(classOf[Text])
    job.setMapOutputValueClass(classOf[IntWritable])
    job.setOutputKeyClass(classOf[Text])
    job.setOutputValueClass(classOf[IntWritable])
    job.setOutputFormatClass(classOf[TextOutputFormat[Text, IntWritable]])

    job.setMapperClass(if (args.imc()) classOf[MyMapper] else classOf[MyMapperIMC])
    if (!args.imc()) job.setCombinerClass(classOf[MyReducer])
    job.setReducerClass(classOf[MyReducer])

    job.setNumReduceTasks(args.reducers());

    val outputDir = new Path(args.output());
    FileSystem.get(conf).delete(outputDir, true);

    val startTime = System.currentTimeMillis();
    job.waitForCompletion(true);
    log.info("Job Finished in " + (System.currentTimeMillis() - startTime) / 1000.0 + " seconds");

    return 0
  }

  def main(args: Array[String]) {
    ToolRunner.run(this, args)
  }
}