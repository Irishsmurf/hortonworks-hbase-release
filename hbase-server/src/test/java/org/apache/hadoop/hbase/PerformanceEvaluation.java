/**
 *
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
package org.apache.hadoop.hbase;

import static org.codehaus.jackson.map.SerializationConfig.Feature.SORT_PROPERTIES_ALPHABETICALLY;

import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.google.common.base.Objects;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionManager;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterAllFilter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.filter.WhileMatchFilter;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Hash;
import org.apache.hadoop.hbase.util.MurmurHash;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.codehaus.jackson.map.ObjectMapper;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.MetricsRegistry;

/**
 * Script used evaluating HBase performance and scalability.  Runs a HBase
 * client that steps through one of a set of hardcoded tests or 'experiments'
 * (e.g. a random reads test, a random writes test, etc.). Pass on the
 * command-line which test to run and how many clients are participating in
 * this experiment. Run {@code PerformanceEvaluation --help} to obtain usage.
 *
 * <p>This class sets up and runs the evaluation programs described in
 * Section 7, <i>Performance Evaluation</i>, of the <a
 * href="http://labs.google.com/papers/bigtable.html">Bigtable</a>
 * paper, pages 8-10.
 *
 * <p>By default, runs as a mapreduce job where each mapper runs a single test
 * client. Can also run as a non-mapreduce, multithreaded application by
 * specifying {@code --nomapred}. Each client does about 1GB of data, unless
 * specified otherwise.
 */
public class PerformanceEvaluation extends Configured implements Tool {
  protected static final Log LOG = LogFactory.getLog(PerformanceEvaluation.class.getName());

  public static final String TABLE_NAME = "TestTable";
  public static final byte[] FAMILY_NAME = Bytes.toBytes("info");
  public static final byte[] QUALIFIER_NAME = Bytes.toBytes("data");
  public static final int VALUE_LENGTH = 1000;
  public static final int ROW_LENGTH = 26;

  private static final int ONE_GB = 1024 * 1024 * 1000;
  private static final int ROWS_PER_GB = ONE_GB / VALUE_LENGTH;
  // TODO : should we make this configurable
  private static final int TAG_LENGTH = 256;
  private static final DecimalFormat FMT = new DecimalFormat("0.##");
  private static final MathContext CXT = MathContext.DECIMAL64;
  private static final BigDecimal MS_PER_SEC = BigDecimal.valueOf(1000);
  private static final BigDecimal BYTES_PER_MB = BigDecimal.valueOf(1024 * 1024);
  private static final TestOptions DEFAULT_OPTS = new TestOptions();

  private static Map<String, CmdDescriptor> COMMANDS = new TreeMap<String, CmdDescriptor>();
  private static final Path PERF_EVAL_DIR = new Path("performance_evaluation");

  static {
    addCommandDescriptor(RandomReadTest.class, "randomRead",
      "Run random read test");
    addCommandDescriptor(RandomSeekScanTest.class, "randomSeekScan",
      "Run random seek and scan 100 test");
    addCommandDescriptor(RandomScanWithRange10Test.class, "scanRange10",
      "Run random seek scan with both start and stop row (max 10 rows)");
    addCommandDescriptor(RandomScanWithRange100Test.class, "scanRange100",
      "Run random seek scan with both start and stop row (max 100 rows)");
    addCommandDescriptor(RandomScanWithRange1000Test.class, "scanRange1000",
      "Run random seek scan with both start and stop row (max 1000 rows)");
    addCommandDescriptor(RandomScanWithRange10000Test.class, "scanRange10000",
      "Run random seek scan with both start and stop row (max 10000 rows)");
    addCommandDescriptor(RandomWriteTest.class, "randomWrite",
      "Run random write test");
    addCommandDescriptor(SequentialReadTest.class, "sequentialRead",
      "Run sequential read test");
    addCommandDescriptor(SequentialWriteTest.class, "sequentialWrite",
      "Run sequential write test");
    addCommandDescriptor(ScanTest.class, "scan",
      "Run scan test (read every row)");
    addCommandDescriptor(FilteredScanTest.class, "filterScan",
      "Run scan test using a filter to find a specific row based on it's value " +
        "(make sure to use --rows=20)");
  }

  /**
   * Enum for map metrics.  Keep it out here rather than inside in the Map
   * inner-class so we can find associated properties.
   */
  protected static enum Counter {
    /** elapsed time */
    ELAPSED_TIME,
    /** number of rows */
    ROWS
  }

  protected static class RunResult implements Comparable<RunResult> {
    public RunResult(long duration, DescriptiveStatistics ds) {
      this.duration = duration;
      this.ds = ds;
    }

    public final long duration;
    public final DescriptiveStatistics ds;

    @Override
    public String toString() {
      return Long.toString(duration);
    }

    @Override public int compareTo(RunResult o) {
      long x = this.duration;
      long y = o.duration;
      return (x < y) ? -1 : ((x == y) ? 0 : 1);
    }
  }

  /**
   * Constructor
   * @param conf Configuration object
   */
  public PerformanceEvaluation(final Configuration conf) {
    super(conf);
  }

  protected static void addCommandDescriptor(Class<? extends Test> cmdClass,
      String name, String description) {
    CmdDescriptor cmdDescriptor = new CmdDescriptor(cmdClass, name, description);
    COMMANDS.put(name, cmdDescriptor);
  }

  /**
   * Implementations can have their status set.
   */
  interface Status {
    /**
     * Sets status
     * @param msg status message
     * @throws IOException
     */
    void setStatus(final String msg) throws IOException;
  }

  /**
   * MapReduce job that runs a performance evaluation client in each map task.
   */
  public static class EvaluationMapTask
      extends Mapper<LongWritable, Text, LongWritable, LongWritable> {

    /** configuration parameter name that contains the command */
    public final static String CMD_KEY = "EvaluationMapTask.command";
    /** configuration parameter name that contains the PE impl */
    public static final String PE_KEY = "EvaluationMapTask.performanceEvalImpl";

    private Class<? extends Test> cmd;
    private PerformanceEvaluation pe;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      this.cmd = forName(context.getConfiguration().get(CMD_KEY), Test.class);

      // this is required so that extensions of PE are instantiated within the
      // map reduce task...
      Class<? extends PerformanceEvaluation> peClass =
          forName(context.getConfiguration().get(PE_KEY), PerformanceEvaluation.class);
      try {
        this.pe = peClass.getConstructor(Configuration.class)
            .newInstance(context.getConfiguration());
      } catch (Exception e) {
        throw new IllegalStateException("Could not instantiate PE instance", e);
      }
    }

    private <Type> Class<? extends Type> forName(String className, Class<Type> type) {
      try {
        return Class.forName(className).asSubclass(type);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("Could not find class for name: " + className, e);
      }
    }

    protected void map(LongWritable key, Text value, final Context context)
           throws IOException, InterruptedException {

      Status status = new Status() {
        public void setStatus(String msg) {
           context.setStatus(msg);
        }
      };

      ObjectMapper mapper = new ObjectMapper();
      TestOptions opts = mapper.readValue(value.toString(), TestOptions.class);
      Configuration conf = HBaseConfiguration.create(context.getConfiguration());

      // Evaluation task
      RunResult result = this.pe.runOneClient(this.cmd, conf, opts, status);
      // Collect how much time the thing took. Report as map output and
      // to the ELAPSED_TIME counter.
      context.getCounter(Counter.ELAPSED_TIME).increment(result.duration);
      context.getCounter(Counter.ROWS).increment(opts.perClientRunRows);
      context.write(new LongWritable(opts.startRow), new LongWritable(result.duration));
      context.progress();
    }
  }

  /*
   * If table does not already exist, create. Also create a table when
   * {@code opts.presplitRegions} is specified or when the existing table's
   * region replica count doesn't match {@code opts.replicas}.
   */
  static boolean checkTable(HBaseAdmin admin, TestOptions opts) throws IOException {
    TableName tableName = TableName.valueOf(opts.tableName);
    boolean needsDelete = false, exists = admin.tableExists(tableName);
    boolean isReadCmd = opts.cmdName.toLowerCase().contains("read")
      || opts.cmdName.toLowerCase().contains("scan");
    if (!exists && isReadCmd) {
      throw new IllegalStateException(
        "Must specify an existing table for read commands. Run a write command first.");
    }
    HTableDescriptor desc =
      exists ? admin.getTableDescriptor(TableName.valueOf(opts.tableName)) : null;
    byte[][] splits = getSplits(opts);

    // recreate the table when user has requested presplit or when existing
    // {RegionSplitPolicy,replica count} does not match requested.
    if ((exists && opts.presplitRegions != DEFAULT_OPTS.presplitRegions)
      || (!isReadCmd && desc != null && desc.getRegionSplitPolicyClassName() != opts.splitPolicy)
      || (!isReadCmd && desc != null && desc.getRegionReplication() != opts.replicas)) {
      needsDelete = true;
      // wait, why did it delete my table?!?
      LOG.debug(Objects.toStringHelper("needsDelete")
        .add("needsDelete", needsDelete)
        .add("isReadCmd", isReadCmd)
        .add("exists", exists)
        .add("desc", desc)
        .add("presplit", opts.presplitRegions)
        .add("splitPolicy", opts.splitPolicy)
        .add("replicas", opts.replicas));
    }

    // remove an existing table
    if (needsDelete) {
      if (admin.isTableEnabled(tableName)) {
        admin.disableTable(tableName);
      }
      admin.deleteTable(tableName);
    }

    // table creation is necessary
    if (!exists || needsDelete) {
      desc = getTableDescriptor(opts);
      if (splits != null) {
        if (LOG.isDebugEnabled()) {
          for (int i = 0; i < splits.length; i++) {
            LOG.debug(" split " + i + ": " + Bytes.toStringBinary(splits[i]));
          }
        }
      }
      admin.createTable(desc, splits);
      LOG.info("Table " + desc + " created");
    }
    return admin.tableExists(tableName);
  }

  /**
   * Create an HTableDescriptor from provided TestOptions.
   */
  protected static HTableDescriptor getTableDescriptor(TestOptions opts) {
    HTableDescriptor desc = new HTableDescriptor(opts.tableName);
    HColumnDescriptor family = new HColumnDescriptor(FAMILY_NAME);
    family.setDataBlockEncoding(opts.blockEncoding);
    family.setCompressionType(opts.compression);
    family.setBloomFilterType(opts.bloomType);
    if (opts.inMemoryCF) {
      family.setInMemory(true);
    }
    desc.addFamily(family);
    if (opts.replicas != DEFAULT_OPTS.replicas) {
      desc.setRegionReplication(opts.replicas);
    }
    if (opts.splitPolicy != DEFAULT_OPTS.splitPolicy) {
      desc.setRegionSplitPolicyClassName(opts.splitPolicy);
    }
    return desc;
  }

  /**
   * generates splits based on total number of rows and specified split regions
   */
  protected static byte[][] getSplits(TestOptions opts) {
    if (opts.presplitRegions == DEFAULT_OPTS.presplitRegions)
      return null;

    int numSplitPoints = opts.presplitRegions - 1;
    byte[][] splits = new byte[numSplitPoints][];
    int jump = opts.totalRows / opts.presplitRegions;
    for (int i = 0; i < numSplitPoints; i++) {
      int rowkey = jump * (1 + i);
      splits[i] = format(rowkey);
    }
    return splits;
  }

  /*
   * Run all clients in this vm each to its own thread.
   */
  static RunResult[] doLocalClients(final TestOptions opts, final Configuration conf)
      throws IOException, InterruptedException {
    final Class<? extends Test> cmd = determineCommandClass(opts.cmdName);
    assert cmd != null;
    Future<RunResult>[] threads = new Future[opts.numClientThreads];
    RunResult[] results = new RunResult[opts.numClientThreads];
    ExecutorService pool = Executors.newFixedThreadPool(opts.numClientThreads,
      new ThreadFactoryBuilder().setNameFormat("TestClient-%s").build());
    for (int i = 0; i < threads.length; i++) {
      final int index = i;
      threads[i] = pool.submit(new Callable<RunResult>() {
        @Override
        public RunResult call() throws Exception {
          TestOptions threadOpts = new TestOptions(opts);
          threadOpts.startRow = index * threadOpts.perClientRunRows;
          RunResult run = runOneClient(cmd, conf, threadOpts, new Status() {
            public void setStatus(final String msg) throws IOException {
              LOG.info("client-" + Thread.currentThread().getName() + " " + msg);
            }
          });
          LOG.info("Finished " + Thread.currentThread().getName() + " in " + run.duration +
            "ms over " + threadOpts.perClientRunRows + " rows");
          return run;
        }
      });
    }
    pool.shutdown();
    for (int i = 0; i < threads.length; i++) {
      try {
        results[i] = threads[i].get();
      } catch (ExecutionException e) {
        throw new IOException(e.getCause());
      }
    }
    final String test = cmd.getSimpleName();
    LOG.info("[" + test + "] Summary of timings (ms): "
             + Arrays.toString(results));
    Arrays.sort(results);
    long total = 0;
    for (int i = 0; i < results.length; i++) {
      total += results[i].duration;
    }
    LOG.info("[" + test + "]"
      + "\tMin: " + results[0] + "ms"
      + "\tMax: " + results[results.length - 1] + "ms"
      + "\tAvg: " + (total / results.length) + "ms");
    return results;
  }

  /*
   * Run a mapreduce job.  Run as many maps as asked-for clients.
   * Before we start up the job, write out an input file with instruction
   * per client regards which row they are to start on.
   * @param cmd Command to run.
   * @throws IOException
   */
  static Job doMapReduce(TestOptions opts, final Configuration conf)
      throws IOException, InterruptedException, ClassNotFoundException {
    final Class<? extends Test> cmd = determineCommandClass(opts.cmdName);
    assert cmd != null;
    Path inputDir = writeInputFile(conf, opts);
    conf.set(EvaluationMapTask.CMD_KEY, cmd.getName());
    conf.set(EvaluationMapTask.PE_KEY, PerformanceEvaluation.class.getName());
    Job job = new Job(conf);
    job.setJarByClass(PerformanceEvaluation.class);
    job.setJobName("HBase Performance Evaluation - " + opts.cmdName);

    job.setInputFormatClass(NLineInputFormat.class);
    NLineInputFormat.setInputPaths(job, inputDir);
    // this is default, but be explicit about it just in case.
    NLineInputFormat.setNumLinesPerSplit(job, 1);

    job.setOutputKeyClass(LongWritable.class);
    job.setOutputValueClass(LongWritable.class);

    job.setMapperClass(EvaluationMapTask.class);
    job.setReducerClass(LongSumReducer.class);

    job.setNumReduceTasks(1);

    job.setOutputFormatClass(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, new Path(inputDir.getParent(), "outputs"));

    TableMapReduceUtil.addDependencyJars(job);
    TableMapReduceUtil.addDependencyJars(job.getConfiguration(),
      DescriptiveStatistics.class, // commons-math
      ObjectMapper.class);         // jackson-mapper-asl

    TableMapReduceUtil.initCredentials(job);

    job.waitForCompletion(true);
    return job;
  }

  /*
   * Write input file of offsets-per-client for the mapreduce job.
   * @param c Configuration
   * @return Directory that contains file written.
   * @throws IOException
   */
  private static Path writeInputFile(final Configuration c, final TestOptions opts) throws IOException {
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
    Path jobdir = new Path(PERF_EVAL_DIR, formatter.format(new Date()));
    Path inputDir = new Path(jobdir, "inputs");

    FileSystem fs = FileSystem.get(c);
    fs.mkdirs(inputDir);

    Path inputFile = new Path(inputDir, "input.txt");
    PrintStream out = new PrintStream(fs.create(inputFile));
    // Make input random.
    Map<Integer, String> m = new TreeMap<Integer, String>();
    Hash h = MurmurHash.getInstance();
    int perClientRows = (opts.totalRows / opts.numClientThreads);
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(SORT_PROPERTIES_ALPHABETICALLY, true);
    try {
      for (int i = 0; i < 10; i++) {
        for (int j = 0; j < opts.numClientThreads; j++) {
          TestOptions next = new TestOptions(opts);
          next.startRow = (j * perClientRows) + (i * (perClientRows/10));
          next.perClientRunRows = perClientRows / 10;
          String s = mapper.writeValueAsString(next);
          int hash = h.hash(Bytes.toBytes(s));
          m.put(hash, s);
        }
      }
      for (Map.Entry<Integer, String> e: m.entrySet()) {
        out.println(e.getValue());
      }
    } finally {
      out.close();
    }
    return inputDir;
  }

  /**
   * Describes a command.
   */
  static class CmdDescriptor {
    private Class<? extends Test> cmdClass;
    private String name;
    private String description;

    CmdDescriptor(Class<? extends Test> cmdClass, String name, String description) {
      this.cmdClass = cmdClass;
      this.name = name;
      this.description = description;
    }

    public Class<? extends Test> getCmdClass() {
      return cmdClass;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }
  }

  /**
   * Wraps up options passed to {@link org.apache.hadoop.hbase.PerformanceEvaluation}.
   * This makes tracking all these arguments a little easier.
   */
  static class TestOptions {

    public TestOptions() {}

    public TestOptions(TestOptions that) {
      this.cmdName = that.cmdName;
      this.nomapred = that.nomapred;
      this.startRow = that.startRow;
      this.perClientRunRows = that.perClientRunRows;
      this.numClientThreads = that.numClientThreads;
      this.totalRows = that.totalRows;
      this.sampleRate = that.sampleRate;
      this.tableName = that.tableName;
      this.flushCommits = that.flushCommits;
      this.writeToWAL = that.writeToWAL;
      this.useTags = that.useTags;
      this.noOfTags = that.noOfTags;
      this.reportLatency = that.reportLatency;
      this.multiGet = that.multiGet;
      this.inMemoryCF = that.inMemoryCF;
      this.presplitRegions = that.presplitRegions;
      this.replicas = that.replicas;
      this.splitPolicy = that.splitPolicy;
      this.compression = that.compression;
      this.blockEncoding = that.blockEncoding;
      this.filterAll = that.filterAll;
      this.bloomType = that.bloomType;
    }

    public String cmdName = null;
    public boolean nomapred = false;
    public boolean filterAll = false;
    public int startRow = 0;
    public int perClientRunRows = ROWS_PER_GB;
    public int numClientThreads = 1;
    public int totalRows = ROWS_PER_GB;
    public float sampleRate = 1.0f;
    public String tableName = TABLE_NAME;
    public boolean flushCommits = true;
    public boolean writeToWAL = true;
    public boolean useTags = false;
    public int noOfTags = 1;
    public boolean reportLatency = false;
    public int multiGet = 0;
    public boolean inMemoryCF = false;
    public int presplitRegions = 0;
    public int replicas = HTableDescriptor.DEFAULT_REGION_REPLICATION;
    public String splitPolicy = null;
    public Compression.Algorithm compression = Compression.Algorithm.NONE;
    public BloomType bloomType = BloomType.ROW;
    public DataBlockEncoding blockEncoding = DataBlockEncoding.NONE;
  }

  /*
   * A test.
   * Subclass to particularize what happens per row.
   */
  static abstract class Test {
    // Below is make it so when Tests are all running in the one
    // jvm, that they each have a differently seeded Random.
    private static final Random randomSeed = new Random(System.currentTimeMillis());
    private static long nextRandomSeed() {
      return randomSeed.nextLong();
    }
    protected final Random rand = new Random(nextRandomSeed());
    protected final Configuration conf;
    protected final TestOptions opts;

    private final Status status;
    protected HConnection connection;
    protected HTableInterface table;
    protected DescriptiveStatistics ds = null;

    /**
     * Note that all subclasses of this class must provide a public contructor
     * that has the exact same list of arguments.
     */
    Test(final Configuration conf, final TestOptions options, final Status status) {
      this.conf = conf;
      this.opts = options;
      this.status = status;
    }

    /**
     * Populated by testTakedown. Only implemented by RandomReadTest at the moment.
     */
    public DescriptiveStatistics getDs() {
      return ds;
    }

    private String generateStatus(final int sr, final int i, final int lr) {
      return sr + "/" + i + "/" + lr;
    }

    protected int getReportingPeriod() {
      int period = opts.perClientRunRows / 10;
      return period == 0 ? opts.perClientRunRows : period;
    }

    void testSetup() throws IOException {
      this.connection = HConnectionManager.createConnection(conf);
      this.table = connection.getTable(opts.tableName);
      this.table.setAutoFlush(false, true);
    }

    void testTakedown() throws IOException {
      if (opts.flushCommits) {
        this.table.flushCommits();
      }
      table.close();
      connection.close();
    }

    /*
     * Run test
     * @return Elapsed time.
     * @throws IOException
     */
    long test() throws IOException {
      testSetup();
      LOG.info("Timed test starting in thread " + Thread.currentThread().getName());
      final long startTime = System.nanoTime();
      try {
        testTimed();
      } finally {
        testTakedown();
      }
      return (System.nanoTime() - startTime) / 1000000;
    }

    /**
     * Provides an extension point for tests that don't want a per row invocation.
     */
    void testTimed() throws IOException {
      int lastRow = opts.startRow + opts.perClientRunRows;
      // Report on completion of 1/10th of total.
      for (int i = opts.startRow; i < lastRow; i++) {
        testRow(i);
        if (status != null && i > 0 && (i % getReportingPeriod()) == 0) {
          status.setStatus(generateStatus(opts.startRow, i, lastRow));
        }
      }
    }

    /*
    * Test for individual row.
    * @param i Row index.
    */
    abstract void testRow(final int i) throws IOException;
  }


  @SuppressWarnings("unused")
  static class RandomSeekScanTest extends Test {
    RandomSeekScanTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      Scan scan = new Scan(getRandomRow(this.rand, opts.totalRows));
      FilterList list = new FilterList();
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      if (opts.filterAll) {
        list.addFilter(new FilterAllFilter());
      }
      list.addFilter(new WhileMatchFilter(new PageFilter(120)));
      scan.setFilter(list);
      ResultScanner s = this.table.getScanner(scan);
      for (Result rr; (rr = s.next()) != null;) ;
      s.close();
    }

    @Override
    protected int getReportingPeriod() {
      int period = opts.perClientRunRows / 100;
      return period == 0 ? opts.perClientRunRows : period;
    }

  }

  @SuppressWarnings("unused")
  static abstract class RandomScanWithRangeTest extends Test {
    RandomScanWithRangeTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      Pair<byte[], byte[]> startAndStopRow = getStartAndStopRow();
      Scan scan = new Scan(startAndStopRow.getFirst(), startAndStopRow.getSecond());
      if (opts.filterAll) {
        scan.setFilter(new FilterAllFilter());
      }
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      ResultScanner s = this.table.getScanner(scan);
      int count = 0;
      for (Result rr; (rr = s.next()) != null;) {
        count++;
      }

      if (i % 100 == 0) {
        LOG.info(String.format("Scan for key range %s - %s returned %s rows",
            Bytes.toString(startAndStopRow.getFirst()),
            Bytes.toString(startAndStopRow.getSecond()), count));
      }

      s.close();
    }

    protected abstract Pair<byte[],byte[]> getStartAndStopRow();

    protected Pair<byte[], byte[]> generateStartAndStopRows(int maxRange) {
      int start = this.rand.nextInt(Integer.MAX_VALUE) % opts.totalRows;
      int stop = start + maxRange;
      return new Pair<byte[],byte[]>(format(start), format(stop));
    }

    @Override
    protected int getReportingPeriod() {
      int period = opts.perClientRunRows / 100;
      return period == 0? opts.perClientRunRows: period;
    }
  }

  static class RandomScanWithRange10Test extends RandomScanWithRangeTest {
    RandomScanWithRange10Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(10);
    }
  }

  static class RandomScanWithRange100Test extends RandomScanWithRangeTest {
    RandomScanWithRange100Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(100);
    }
  }

  static class RandomScanWithRange1000Test extends RandomScanWithRangeTest {
    RandomScanWithRange1000Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(1000);
    }
  }

  static class RandomScanWithRange10000Test extends RandomScanWithRangeTest {
    RandomScanWithRange10000Test(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(10000);
    }
  }

  static class RandomReadTest extends Test {
    private final int everyN;
    private final double[] times;
    private final Consistency consistency;
    private ArrayList<Get> gets;
    int idx = 0;

    RandomReadTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
      everyN = (int) (opts.totalRows / (opts.totalRows * opts.sampleRate));
      consistency = options.replicas == DEFAULT_OPTS.replicas ? null : Consistency.TIMELINE;
      LOG.info("Sampling 1 every " + everyN + " out of " + opts.perClientRunRows + " total rows.");
      if (opts.multiGet > 0) {
        LOG.info("MultiGet enabled. Sending GETs in batches of " + opts.multiGet + ".");
        this.gets = new ArrayList<Get>(opts.multiGet);
      }
      if (opts.reportLatency) {
        this.times = new double[(int) Math.ceil(opts.perClientRunRows * opts.sampleRate / Math.max(1, opts.multiGet))];
      } else {
        this.times = null;
      }
    }

    @Override
    void testRow(final int i) throws IOException {
      if (i % everyN == 0) {
        Get get = new Get(getRandomRow(this.rand, opts.totalRows));
        get.addColumn(FAMILY_NAME, QUALIFIER_NAME);
        get.setConsistency(consistency);
        if (opts.filterAll) {
          get.setFilter(new FilterAllFilter());
        }
        if (opts.multiGet > 0) {
          this.gets.add(get);
          if (this.gets.size() == opts.multiGet) {
            long start = System.nanoTime();
            this.table.get(this.gets);
            if (opts.reportLatency) {
              times[idx++] = (System.nanoTime() - start) / 1e6;
            }
            this.gets.clear();
          }
        } else {
          long start = System.nanoTime();
          this.table.get(get);
          if (opts.reportLatency) {
            times[idx++] = (System.nanoTime() - start) / 1e6;
          }
        }
      }
    }

    @Override
    protected int getReportingPeriod() {
      int period = opts.perClientRunRows / 100;
      return period == 0 ? opts.perClientRunRows : period;
    }

    @Override
    protected void testTakedown() throws IOException {
      if (this.gets != null && this.gets.size() > 0) {
        this.table.get(gets);
        this.gets.clear();
      }
      super.testTakedown();
      if (opts.reportLatency) {
        Arrays.sort(times);
        ds = new DescriptiveStatistics();
        for (double t : times) {
          ds.addValue(t);
        }
        LOG.info("randomRead latency log (ms), on " + times.length + " measures");
        LOG.info("99.9999% = " + ds.getPercentile(99.9999d));
        LOG.info(" 99.999% = " + ds.getPercentile(99.999d));
        LOG.info("  99.99% = " + ds.getPercentile(99.99d));
        LOG.info("   99.9% = " + ds.getPercentile(99.9d));
        LOG.info("     99% = " + ds.getPercentile(99d));
        LOG.info("     95% = " + ds.getPercentile(95d));
        LOG.info("     90% = " + ds.getPercentile(90d));
        LOG.info("     80% = " + ds.getPercentile(80d));
        LOG.info("Standard Deviation = " + ds.getStandardDeviation());
        LOG.info("Mean = " + ds.getMean());
      }
    }
  }

  static class RandomWriteTest extends Test {
    RandomWriteTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      byte[] row = getRandomRow(this.rand, opts.totalRows);
      Put put = new Put(row);
      byte[] value = generateData(this.rand, VALUE_LENGTH);
      if (opts.useTags) {
        byte[] tag = generateData(this.rand, TAG_LENGTH);
        Tag[] tags = new Tag[opts.noOfTags];
        for (int n = 0; n < opts.noOfTags; n++) {
          Tag t = new Tag((byte) n, tag);
          tags[n] = t;
        }
        KeyValue kv = new KeyValue(row, FAMILY_NAME, QUALIFIER_NAME, HConstants.LATEST_TIMESTAMP,
            value, tags);
        put.add(kv);
      } else {
        put.add(FAMILY_NAME, QUALIFIER_NAME, value);
      }
      put.setDurability(opts.writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      table.put(put);
    }
  }


  static class ScanTest extends Test {
    private ResultScanner testScanner;

    ScanTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testTakedown() throws IOException {
      if (this.testScanner != null) {
        this.testScanner.close();
      }
      super.testTakedown();
    }


    @Override
    void testRow(final int i) throws IOException {
      if (this.testScanner == null) {
        Scan scan = new Scan(format(opts.startRow));
        scan.setCaching(30);
        scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
        if (opts.filterAll) {
          scan.setFilter(new FilterAllFilter());
        }
       this.testScanner = table.getScanner(scan);
      }
      testScanner.next();
    }

  }

  static class SequentialReadTest extends Test {
    SequentialReadTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      Get get = new Get(format(i));
      get.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      if (opts.filterAll) {
        get.setFilter(new FilterAllFilter());
      }
      table.get(get);
    }
  }

  static class SequentialWriteTest extends Test {
    SequentialWriteTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(final int i) throws IOException {
      byte[] row = format(i);
      Put put = new Put(row);
      byte[] value = generateData(this.rand, VALUE_LENGTH);
      if (opts.useTags) {
        byte[] tag = generateData(this.rand, TAG_LENGTH);
        Tag[] tags = new Tag[opts.noOfTags];
        for (int n = 0; n < opts.noOfTags; n++) {
          Tag t = new Tag((byte) n, tag);
          tags[n] = t;
        }
        KeyValue kv = new KeyValue(row, FAMILY_NAME, QUALIFIER_NAME, HConstants.LATEST_TIMESTAMP,
            value, tags);
        put.add(kv);
      } else {
        put.add(FAMILY_NAME, QUALIFIER_NAME, value);
      }
      put.setDurability(opts.writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      table.put(put);
    }
  }

  static class FilteredScanTest extends Test {
    protected static final Log LOG = LogFactory.getLog(FilteredScanTest.class.getName());

    FilteredScanTest(Configuration conf, TestOptions options, Status status) {
      super(conf, options, status);
    }

    @Override
    void testRow(int i) throws IOException {
      byte[] value = generateData(this.rand, VALUE_LENGTH);
      Scan scan = constructScan(value);
      ResultScanner scanner = null;
      try {
        scanner = this.table.getScanner(scan);
        while (scanner.next() != null) {
        }
      } finally {
        if (scanner != null) scanner.close();
      }
    }

    protected Scan constructScan(byte[] valuePrefix) throws IOException {
      FilterList list = new FilterList();
      Filter filter = new SingleColumnValueFilter(
          FAMILY_NAME, QUALIFIER_NAME, CompareFilter.CompareOp.EQUAL,
          new BinaryComparator(valuePrefix)
      );
      list.addFilter(filter);
      if(opts.filterAll) {
        list.addFilter(new FilterAllFilter());
      }
      Scan scan = new Scan();
      scan.addColumn(FAMILY_NAME, QUALIFIER_NAME);
      scan.setFilter(list);
      return scan;
    }
  }

  /**
   * Compute a throughput rate in MB/s.
   * @param rows Number of records consumed.
   * @param timeMs Time taken in milliseconds.
   * @return String value with label, ie '123.76 MB/s'
   */
  private static String calculateMbps(int rows, long timeMs) {
    // MB/s = ((totalRows * ROW_SIZE_BYTES) / totalTimeMS)
    //        * 1000 MS_PER_SEC / (1024 * 1024) BYTES_PER_MB
    BigDecimal rowSize =
      BigDecimal.valueOf(ROW_LENGTH + VALUE_LENGTH + FAMILY_NAME.length + QUALIFIER_NAME.length);
    BigDecimal mbps = BigDecimal.valueOf(rows).multiply(rowSize, CXT)
      .divide(BigDecimal.valueOf(timeMs), CXT).multiply(MS_PER_SEC, CXT)
      .divide(BYTES_PER_MB, CXT);
    return FMT.format(mbps) + " MB/s";
  }

  /*
   * Format passed integer.
   * @param number
   * @return Returns zero-prefixed ROW_LENGTH-byte wide decimal version of passed
   * number (Does absolute in case number is negative).
   */
  public static byte [] format(final int number) {
    byte [] b = new byte[ROW_LENGTH];
    int d = Math.abs(number);
    for (int i = b.length - 1; i >= 0; i--) {
      b[i] = (byte)((d % 10) + '0');
      d /= 10;
    }
    return b;
  }

  /*
   * This method takes some time and is done inline uploading data.  For
   * example, doing the mapfile test, generation of the key and value
   * consumes about 30% of CPU time.
   * @return Generated random value to insert into a table cell.
   */
  public static byte[] generateData(final Random r, int length) {
    byte [] b = new byte [length];
    int i = 0;

    for(i = 0; i < (length-8); i += 8) {
      b[i] = (byte) (65 + r.nextInt(26));
      b[i+1] = b[i];
      b[i+2] = b[i];
      b[i+3] = b[i];
      b[i+4] = b[i];
      b[i+5] = b[i];
      b[i+6] = b[i];
      b[i+7] = b[i];
    }

    byte a = (byte) (65 + r.nextInt(26));
    for(; i < length; i++) {
      b[i] = a;
    }
    return b;
  }

  /**
   * @deprecated Use {@link #generateData(java.util.Random, int)} instead.
   * @return Generated random value to insert into a table cell.
   */
  @Deprecated
  public static byte[] generateValue(final Random r) {
    return generateData(r, VALUE_LENGTH);
  }

  static byte [] getRandomRow(final Random random, final int totalRows) {
    return format(random.nextInt(Integer.MAX_VALUE) % totalRows);
  }

  static RunResult runOneClient(final Class<? extends Test> cmd, Configuration conf, TestOptions opts,
    final Status status)
      throws IOException {
    status.setStatus("Start " + cmd + " at offset " + opts.startRow + " for " +
      opts.perClientRunRows + " rows");
    long totalElapsedTime = 0;

    final Test t;
    try {
      Constructor<? extends Test> constructor =
        cmd.getDeclaredConstructor(Configuration.class, TestOptions.class, Status.class);
      t = constructor.newInstance(conf, opts, status);
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("Invalid command class: " +
          cmd.getName() + ".  It does not provide a constructor as described by " +
          "the javadoc comment.  Available constructors are: " +
          Arrays.toString(cmd.getConstructors()));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to construct command class", e);
    }
    totalElapsedTime = t.test();

    status.setStatus("Finished " + cmd + " in " + totalElapsedTime +
      "ms at offset " + opts.startRow + " for " + opts.perClientRunRows + " rows" +
      " (" + calculateMbps((int)(opts.perClientRunRows * opts.sampleRate), totalElapsedTime) + ")");
    return new RunResult(totalElapsedTime, t.getDs());
  }

  private void runTest(final Class<? extends Test> cmd, TestOptions opts) throws IOException,
      InterruptedException, ClassNotFoundException {
    HBaseAdmin admin = null;
    try {
      admin = new HBaseAdmin(getConf());
      checkTable(admin, opts);
    } finally {
      if (admin != null) admin.close();
    }
    if (opts.nomapred) {
      doLocalClients(opts, getConf());
    } else {
      doMapReduce(opts, getConf());
    }
  }

  protected void printUsage() {
    printUsage(null);
  }

  protected void printUsage(final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.println("Usage: java " + this.getClass().getName() + " \\");
    System.err.println("  [--nomapred] [--rows=ROWS] [--table=NAME] \\");
    System.err.println("  [--compress=TYPE] [--blockEncoding=TYPE] " +
      "[-D<property=value>]* <command> <nclients>");
    System.err.println();
    System.err.println("Options:");
    System.err.println(" nomapred        Run multiple clients using threads " +
      "(rather than use mapreduce)");
    System.err.println(" rows            Rows each client runs. Default: One million");
    System.err.println(" sampleRate      Execute test on a sample of total " +
      "rows. Only supported by randomRead. Default: 1.0");
    System.err.println(" table           Alternate table name. Default: 'TestTable'");
    System.err.println(" compress        Compression type to use (GZ, LZO, ...). Default: 'NONE'");
    System.err.println(" flushCommits    Used to determine if the test should flush the table. " +
      "Default: false");
    System.err.println(" writeToWAL      Set writeToWAL on puts. Default: True");
    System.err.println(" presplit        Create presplit table. Recommended for accurate perf " +
      "analysis (see guide).  Default: disabled");
    System.err.println(" inmemory        Tries to keep the HFiles of the CF " +
      "inmemory as far as possible. Not guaranteed that reads are always served " +
      "from memory.  Default: false");
    System.err.println(" usetags         Writes tags along with KVs. Use with HFile V3. " +
      "Default: false");
    System.err.println(" numoftags       Specify the no of tags that would be needed. " +
       "This works only if usetags is true.");
    System.err.println(" filterAll       Helps to filter out all the rows on the server side"
        + " there by not returning any thing back to the client.  Helps to check the server side"
        + " performance.  Uses FilterAllFilter internally. ");
    System.err.println(" latency         Set to report operation latencies. " +
      "Currently only supported by randomRead test. Default: False");
    System.err.println(" multiGet        Batch gets together into groups of N. Only supported " +
      "by randomRead. Default: disabled");
    System.err.println(" replicas        Enable region replica testing. Defaults: 1.");
    System.err.println(" splitPolicy     Specify a custom RegionSplitPolicy for the table.");
    System.err.println(" bloomFilter      Bloom filter type, one of " + Arrays.toString(BloomType.values()));
    System.err.println();
    System.err.println(" Note: -D properties will be applied to the conf used. ");
    System.err.println("  For example: ");
    System.err.println("   -Dmapred.output.compress=true");
    System.err.println("   -Dmapreduce.task.timeout=60000");
    System.err.println();
    System.err.println("Command:");
    for (CmdDescriptor command : COMMANDS.values()) {
      System.err.println(String.format(" %-15s %s", command.getName(), command.getDescription()));
    }
    System.err.println();
    System.err.println("Args:");
    System.err.println(" nclients        Integer. Required. Total number of " +
      "clients (and HRegionServers)");
    System.err.println("                 running: 1 <= value <= 500");
    System.err.println("Examples:");
    System.err.println(" To run a single evaluation client:");
    System.err.println(" $ bin/hbase " + this.getClass().getName()
        + " sequentialWrite 1");
  }

  /**
   * Parse options passed in via an arguments array. Assumes that array has been split
   * on white-space and placed into a {@code Queue}. Any unknown arguments will remain
   * in the queue at the conclusion of this method call. It's up to the caller to deal
   * with these unrecognized arguments.
   */
  static TestOptions parseOpts(Queue<String> args) {
    TestOptions opts = new TestOptions();

    String cmd = null;
    while ((cmd = args.poll()) != null) {
      if (cmd.equals("-h") || cmd.startsWith("--h")) {
        // place item back onto queue so that caller knows parsing was incomplete
        args.add(cmd);
        break;
      }

      final String nmr = "--nomapred";
      if (cmd.startsWith(nmr)) {
        opts.nomapred = true;
        continue;
      }

      final String rows = "--rows=";
      if (cmd.startsWith(rows)) {
        opts.perClientRunRows = Integer.parseInt(cmd.substring(rows.length()));
        continue;
      }

      final String sampleRate = "--sampleRate=";
      if (cmd.startsWith(sampleRate)) {
        opts.sampleRate = Float.parseFloat(cmd.substring(sampleRate.length()));
        continue;
      }

      final String table = "--table=";
      if (cmd.startsWith(table)) {
        opts.tableName = cmd.substring(table.length());
        continue;
      }

      final String compress = "--compress=";
      if (cmd.startsWith(compress)) {
        opts.compression = Compression.Algorithm.valueOf(cmd.substring(compress.length()));
        continue;
      }

      final String blockEncoding = "--blockEncoding=";
      if (cmd.startsWith(blockEncoding)) {
        opts.blockEncoding = DataBlockEncoding.valueOf(cmd.substring(blockEncoding.length()));
        continue;
      }

      final String flushCommits = "--flushCommits=";
      if (cmd.startsWith(flushCommits)) {
        opts.flushCommits = Boolean.parseBoolean(cmd.substring(flushCommits.length()));
        continue;
      }

      final String writeToWAL = "--writeToWAL=";
      if (cmd.startsWith(writeToWAL)) {
        opts.writeToWAL = Boolean.parseBoolean(cmd.substring(writeToWAL.length()));
        continue;
      }

      final String presplit = "--presplit=";
      if (cmd.startsWith(presplit)) {
        opts.presplitRegions = Integer.parseInt(cmd.substring(presplit.length()));
        continue;
      }

      final String inMemory = "--inmemory=";
      if (cmd.startsWith(inMemory)) {
        opts.inMemoryCF = Boolean.parseBoolean(cmd.substring(inMemory.length()));
        continue;
      }

      final String latency = "--latency";
      if (cmd.startsWith(latency)) {
        opts.reportLatency = true;
        continue;
      }

      final String multiGet = "--multiGet=";
      if (cmd.startsWith(multiGet)) {
        opts.multiGet = Integer.parseInt(cmd.substring(multiGet.length()));
        continue;
      }

      final String useTags = "--usetags=";
      if (cmd.startsWith(useTags)) {
        opts.useTags = Boolean.parseBoolean(cmd.substring(useTags.length()));
        continue;
      }

      final String noOfTags = "--nooftags=";
      if (cmd.startsWith(noOfTags)) {
        opts.noOfTags = Integer.parseInt(cmd.substring(noOfTags.length()));
        continue;
      }

      final String filterOutAll = "--filterAll";
      if (cmd.startsWith(filterOutAll)) {
        opts.filterAll = true;
        continue;
      }
        
      final String replicas = "--replicas=";
      if (cmd.startsWith(replicas)) {
        opts.replicas = Integer.parseInt(cmd.substring(replicas.length()));
        continue;
      }

      final String bloomFilter = "--bloomFilter";
      if (cmd.startsWith(bloomFilter)) {
        opts.bloomType = BloomType.valueOf(cmd.substring(bloomFilter.length()));
        continue;
      }

      final String splitPolicy = "--splitPolicy=";
      if (cmd.startsWith(splitPolicy)) {
        opts.splitPolicy = cmd.substring(splitPolicy.length());
        continue;
      }

      if (isCommandClass(cmd)) {
        opts.cmdName = cmd;
        opts.numClientThreads = Integer.parseInt(args.remove());
        // number of rows specified
        opts.totalRows = opts.perClientRunRows * opts.numClientThreads;
        break;
      }
    }
    return opts;
  }

  public int run(String[] args) throws Exception {
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
    int errCode = -1;
    if (args.length < 1) {
      printUsage();
      return errCode;
    }

    try {
      LinkedList<String> argv = new LinkedList<String>();
      argv.addAll(Arrays.asList(args));
      TestOptions opts = parseOpts(argv);

      // args remainting, print help and exit
      if (!argv.isEmpty()) {
        errCode = 0;
        printUsage();
      }

      // must run at least 1 client
      if (opts.numClientThreads <= 0) {
        throw new IllegalArgumentException("Number of clients must be > 0");
      }

      Class<? extends Test> cmdClass = determineCommandClass(opts.cmdName);
      if (cmdClass != null) {
        runTest(cmdClass, opts);
        errCode = 0;
      }

    } catch (Exception e) {
      e.printStackTrace();
    }

    return errCode;
  }

  private static boolean isCommandClass(String cmd) {
    return COMMANDS.containsKey(cmd);
  }

  private static Class<? extends Test> determineCommandClass(String cmd) {
    CmdDescriptor descriptor = COMMANDS.get(cmd);
    return descriptor != null ? descriptor.getCmdClass() : null;
  }

  public static void main(final String[] args) throws Exception {
    int res = ToolRunner.run(new PerformanceEvaluation(HBaseConfiguration.create()), args);
    System.exit(res);
  }
}
