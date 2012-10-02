package org.commoncrawl.mapred.ec2.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.commoncrawl.mapred.ec2.parser.EC2ParserTask.QueueItem;
import org.commoncrawl.mapred.ec2.parser.EC2ParserTask.QueueTask;
import org.commoncrawl.protocol.ParseOutput;
import org.commoncrawl.util.CCStringUtils;
import org.commoncrawl.util.JobBuilder;
import org.commoncrawl.util.TaskDataUtils;
import org.commoncrawl.util.Tuples.Pair;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.common.collect.TreeMultiset;
/** 
 * 
 * The CC EC2 workflow involves running the EC2ParserTask, which ingests RAW crawl logs (data) and produces  
 * ARC, metadata and text files for crawled content. The parse jobs run on EC2 in an EMR/Spot Instance context, 
 * so smooth job performance is important to prevent lagging mappers from reducing cluster utilization and thus 
 * wasting expensive compute resources. To resolve this requirement, the parse job will fail-fast mappers that
 * either take too long or those that create too many failures. A scheme has been put into place whereby failed 
 * splits are tracked. This task (the Checkpoint Task) is run after a Parse run has completed and its job is to 
 * collect all the failed splits, group them into a unit called a 'Checkpoint' and then run them in a modified
 * (potentially less expensive, longer running) job context, to try and achieve as close to 100% coverage of the 
 * raw crawl data. This task creates a staged checkpoint directory, under which it creates a set of segments, each
 * of which contains a set of failed splits from previous parse runs. It subsequently runs map-reduce jobs to parse 
 * these segments. Once all segments have been parsed (as best as possible), the 'staged' checkpoint is promoted to a 
 * real checkpoint by having it move from the staged_checkpoint directory to the checkpoint directory. In the process,
 * all segments that were processed within the context of the checkpoint are promoted to be 'real segments', and are 
 * thus added to the valid_segments list and are made visible to all consumers of the data.     
 * 
 * @author rana
 *
 */
public class EC2CheckpointTask extends EC2TaskDataAwareTask { 
  
  public EC2CheckpointTask(Configuration conf) throws IOException {
    super(conf);

  }

  public static final Log LOG = LogFactory.getLog(EC2CheckpointTask.class);
  
  public static void main(String[] args)throws IOException {
    Configuration conf = new Configuration();
    FileSystem fs;
    try {
      fs = FileSystem.get(new URI("s3n://aws-publicdatasets"),conf);
    } catch (URISyntaxException e) {
      throw new IOException(e.toString());
    }
    System.out.println("FileSystem is:" + fs.getUri() +" Scanning for valid checkpoint id");
    long latestCheckpointId = findLastValidCheckpointId(fs,conf);
    System.out.println("Latest Checkpoint Id is:"+ latestCheckpointId);
    
    EC2CheckpointTask task = new EC2CheckpointTask(conf);
    
    System.out.println("Performing checkpoint");
    task.doCheckpoint(fs, conf);
    System.out.println("checkpoint complete");
    task.shutdown();
    
    System.exit(0);
  }
  
  /**
   * return the last valid checkpoint id or -1 if no checkpoints exist
   * 
   * @param fs
   * @param conf
   * @return
   * @throws IOException
   */
  static long findLastValidCheckpointId(FileSystem fs, Configuration conf)throws IOException {
    long lastCheckpointId = -1L;
    for (FileStatus dirStats : fs.globStatus(new Path(CHECKPOINTS_PATH,"[0-9]*"))) {  
      lastCheckpointId = Math.max(lastCheckpointId,Long.parseLong(dirStats.getPath().getName()));
    }
    return lastCheckpointId;
  }
  
  /** 
   * return the currently active checkpoint's id or -1 if no active checkpoint 
   * @param fs
   * @param conf
   * @return
   * @throws IOException
   */
  static long findStagedCheckpointId(FileSystem fs,Configuration conf)throws IOException { 
    FileStatus[] intermediateCheckpoints = fs.globStatus(new Path(CHECKPOINTS_PATH,"[0-9]*"));
    if (intermediateCheckpoints.length > 1) { 
      throw new IOException("More than one Staged Checkpoint Found!:" + intermediateCheckpoints);
    }
    else if (intermediateCheckpoints.length == 1) { 
      return Long.parseLong(intermediateCheckpoints[0].getPath().getName()); 
    }
    return -1L;
  }
  
  static Pattern arcFileNamePattern = Pattern.compile("^([0-9]*)_([0-9]*).arc.gz$");
  
  static Multimap<Integer,Long> getArcFilesSizesSegment(FileSystem fs,long segmentId) throws IOException  {
    
    Multimap<Integer,Long> splitToSizeMap = TreeMultimap.create();
    
    for (FileStatus arcCandidate : fs.globStatus(new Path(SEGMENTS_PATH + segmentId,"*.arc.gz"))) { 
      Matcher m = arcFileNamePattern.matcher(arcCandidate.getPath().getName());
      if (m.matches() && m.groupCount() == 2) { 
        int splitId = Integer.parseInt(m.group(2));
        splitToSizeMap.put(splitId,arcCandidate.getLen());
      }
    }
    return splitToSizeMap;
  }
    
  
  /** 
   * Given a list of Splits from a set of previosuly completed segments, construct a set of checkpoint segments 
   * and distribute splits amongst them 
   * 
   * @param fs
   * @param segmentOutputPath
   * @param splitDetails
   * @param baseSegmentId
   * @param defaultSplitSize
   * @param idealSplitsPerSegment
   * @throws IOException
   */
  static void buildSplitsForCheckpoint(FileSystem fs,Path segmentOutputPath,List<SegmentSplitDetail> splitDetails,long baseSegmentId, int defaultSplitSize, int idealSplitsPerSegment) throws IOException {
    ArrayList<FileSplit> splits = new ArrayList<FileSplit>();
    
    for (SegmentSplitDetail splitDetail : splitDetails) { 
      SplitInfo splitItem = null;
      if (splitDetail.isPartialSplit()) { 
        splitItem = splitDetail.partialSplit; 
      }
      else { 
        splitItem = splitDetail.originalSplit;
      }
      
      long splitBytes = splitItem.length;
      long splitOffset = splitItem.offset;
      int splitCount = (int) (splitBytes / defaultSplitSize);
      // if split bytes is less than default split size or the trailing bytes in the last 
      // split is >= 1/2 split size then add an additional split item to the list ... 
      if (splitCount == 0 || splitBytes % defaultSplitSize >= defaultSplitSize / 2) { 
        splitCount ++;
      }
      // now ... emit splits ... 
      for (int i=0;i<splitCount;++i) {
        long splitSize = defaultSplitSize;
        
        // gobble up all remaining bytes for trailing split ... 
        if (i == splitCount -1) { 
          splitSize = splitBytes;
        }
        // create split ... 
        FileSplit split = new FileSplit(new Path(splitItem.sourceFilePath),splitOffset, splitSize, (String[])null);
        // add split 
        splits.add(split);
        // increment counters 
        splitBytes -= splitSize;
        splitOffset += splitSize;
      }
    }
    
    // ok, now collect segments .. doing basic partitioning ... 
    List<List<FileSplit>> segments = Lists.partition(splits, idealSplitsPerSegment);
    
    long segmentId = baseSegmentId;
    
    // now emit the segments ...
    for (List<FileSplit> segmentSplits : segments) { 
      // establish split path ... 
      Path splitManifestPath = new Path(segmentOutputPath + Long.toString(segmentId) +"/" + SPLITS_MANIFEST_FILE);
      // write manifest ... 
      listToTextFile(segmentSplits,fs,splitManifestPath);
    }
  }
  
  /** 
   * 
   *  load checkpoint state from either a partially completed checkpoint, or a newly constructed checkpoint
   *  
   * @param fs
   * @param conf
   * @return
   * @throws IOException
   */
  static Pair<Long,Multimap<Long,SplitInfo>> findOrCreateCheckpoint(FileSystem fs,Configuration conf) throws IOException { 
    
    Multimap<Long,SplitInfo> segmentsAndSplits = null;
    
    long stagedCheckpointId = findStagedCheckpointId(fs, conf);
    if (stagedCheckpointId == -1) {
      // create a base segment id ... 
      long baseSegmentId = System.currentTimeMillis();
      // and create a new checkpoint id ...
      stagedCheckpointId = baseSegmentId + 10000;
      // get last valid checkpoint id ... 
      long lastValidCheckpointId = findLastValidCheckpointId(fs, conf);
      // iterate available segments (past last checkpoint date), collecting a list of partial of failed splits ... 
      List<SegmentSplitDetail> splitDetails = iterateAvailableSegmentsCollectSplits(fs,lastValidCheckpointId);
      
      if (splitDetails.size() != 0) {
        try { 
          // write source split details to disk ... 
          listToTextFile(splitDetails,fs,new Path(CHECKPOINT_STAGING_PATH +Long.toString(stagedCheckpointId)+"/"+SPLITS_MANIFEST_FILE));
          // given the list of failed/partial splits, ditribute them to a set of segments ...  
          buildSplitsForCheckpoint(fs,new Path(CHECKPOINT_STAGING_PATH + Long.toString(stagedCheckpointId)+"/"),splitDetails,baseSegmentId,DEFAULT_PARSER_CHECKPOINT_JOB_SPLIT_SIZE,DEFAULT_PARSER_CHECKPOINT_SPLITS_PER_JOB);
        }
        catch (Exception e) { 
          LOG.error("Failed to create checkpoint segment:" + stagedCheckpointId + " Exception:"+ CCStringUtils.stringifyException(e));
          fs.delete(new Path(CHECKPOINT_STAGING_PATH + Long.toString(stagedCheckpointId)+"/"), true);
        }
      }
      else { 
        throw new IOException("No Valid Splits Found for Checkpoint!");
      }
    }
    
    // ok read in the splits ... 
    segmentsAndSplits = TreeMultimap.create();
    // load the segments and splits from disk ...
    for (FileStatus stagedSegment : fs.globStatus(new Path(CHECKPOINT_STAGING_PATH + Long.toString(stagedCheckpointId)+"/"+ "[0-9]*"))) {
      long segmentId = Long.parseLong(stagedSegment.getPath().getName());
      for (SegmentSplitDetail splitDetail :   getSplitDetailsFromFile(fs,segmentId,new Path(stagedSegment.getPath(),"SPLITS_MANIFEST_FILE"),SPLITS_MANIFEST_FILE)) {
        segmentsAndSplits.put(segmentId, splitDetail.originalSplit);
      }
    }
    return new Pair<Long,Multimap<Long,SplitInfo>>(stagedCheckpointId,segmentsAndSplits);
  }
  
  /** 
   * filter out incomplete segments given a set of checkpoint segments
   * 
   * @param fs
   * @param conf
   * @param checkpointDetail
   * @return
   * @throws IOException
   */
  static private List<Pair<Long,Collection<SplitInfo>>> filterCompletedSegments(FileSystem fs,Configuration conf,Pair<Long,Multimap<Long,SplitInfo>> checkpointDetail)throws IOException { 
    ArrayList<Pair<Long,Collection<SplitInfo>>> segmentListOut= new ArrayList<Pair<Long,Collection<SplitInfo>>>();
    // iterate segments ...
    for (long segmentId : checkpointDetail.e1.keys()) { 
      // establish path ...
      Path segmentPath = new Path(CHECKPOINT_STAGING_PATH + checkpointDetail.e0 +"/" + segmentId+"/");
      // establish success file path 
      Path successFile = new Path(segmentPath,JOB_SUCCESS_FILE);
      // and output path 
      Path outputPath = new Path(segmentPath,CHECKPOINT_JOB_OUTPUT_PATH);
      
      // check to see if job already completed  
      if (!fs.exists(successFile)) { 
        // check to see if output folder exists... if so,delete it ... 
        if (fs.exists(outputPath)) { 
          LOG.info("Existing output folder located for segment:"+ segmentId + ". Deleting folder");
          fs.delete(outputPath, true);
        }
        segmentListOut.add(new Pair<Long, Collection<SplitInfo>>(segmentId,checkpointDetail.e1.values()));
      }
    }
    return segmentListOut;
  }
  
  static final int MAX_SIMULTANEOUS_JOBS = 100;

  LinkedBlockingQueue<QueueItem> _queue = new LinkedBlockingQueue<QueueItem>();
  Semaphore jobThreadSemaphore = null;
  int maxSimultaneousJobs = MAX_SIMULTANEOUS_JOBS;

  /** 
   * helper class used to queue individual checkpoint segments for map-reduce processing 
   * @author rana
   *
   */
  static class QueueItem {
    QueueItem() { 
      
    }
    
    QueueItem(FileSystem fs,Configuration conf,long checkpointId,Pair<Long,Collection<SplitInfo>> segmentDetail) { 
      this.conf = conf;
      this.fs = fs;
      this.checkpointId = checkpointId;
      this.segmentDetail = segmentDetail;
    }
    
    public Configuration conf;
    public FileSystem fs;
    public long checkpointId;
    public Pair<Long,Collection<SplitInfo>> segmentDetail;
  }
  
  /** 
   * Given a file system pointer (s3n) and a configuration, scan all previously processed segments that 
   * are NOT part of a previous checkpoint, and build a list of partially completed splits and fails splits.
   * Next, create a new checkpoint, and distribute the splits amongst a new set of segments (within the context of 
   * the checkpoint). Then queue up the segments for re-processing via map-reduce. Once all checkpoint segments have 
   * been processed, promote the 'staged' checkpoint to a real checkpoint.    
   * 
   * @param fs
   * @param conf
   * @throws IOException
   */
  public void doCheckpoint(final FileSystem fs,final Configuration conf)throws IOException { 
    LOG.info("Starting Checkpoint. Searching for existing or creating new staged checkpoint");
    final Pair<Long,Multimap<Long,SplitInfo>> checkpointInfo = findOrCreateCheckpoint(fs, conf);
    LOG.info("Checkpoint Id is:"+ checkpointInfo.e0);
    List<Pair<Long,Collection<SplitInfo>>> validSegments = filterCompletedSegments(fs, conf, checkpointInfo);
    LOG.info("Queueing Segments. There are:" + validSegments.size() + " segments out of a total of:" + checkpointInfo.e1.keys().size());
    for (Pair<Long,Collection<SplitInfo>> segmentInfo : validSegments) { 
      try {
        _queue.put(new QueueItem(fs, conf, checkpointInfo.e0, segmentInfo));
      } catch (InterruptedException e) {
      }
    }

    // queue shutdown items 
    for (int i=0;i<maxSimultaneousJobs;++i) { 
      try {
        _queue.put(new QueueItem());
      } catch (InterruptedException e) {
      }
    }

    LOG.info("Starting Threads");
    // startup threads .. 
    for (int i=0;i<maxSimultaneousJobs;++i) { 
      Thread thread = new Thread(new QueueTask());
      thread.start();
    }
    
        
    // ok wait for them to die
    LOG.info("Waiting for Queue Threads to Die");
    jobThreadSemaphore.acquireUninterruptibly();
    
    // TODO: WE NEED TO PROMOTE A COMPLETED CHECKPOINT HERE ... 
  }
  
  /** 
   * Worker Thread that actually submits jobs to the TT
   * 
   * @author rana
   *
   */
  class QueueTask implements Runnable {


    @Override
    public void run() {
      while (true) {
        LOG.info("Queue Thread:" + Thread.currentThread().getId() + " Running");
        try {
          QueueItem item = _queue.take();
          
          
          if (item.segmentDetail != null) { 
            LOG.info("Queue Thread:" + Thread.currentThread().getId() + " got segment:" + item.segmentDetail.e0);
            LOG.info("Queue Thread:" + Thread.currentThread().getId() + " Starting Job");
            try {
              parse(item.fs,item.conf,item.checkpointId,item.segmentDetail);
            } catch (IOException e) {
              LOG.error("Queue Thread:" + Thread.currentThread().getId() + " threw exception:" + CCStringUtils.stringifyException(e));
            }
          }
          else { 
            LOG.info("Queue Thread:" + Thread.currentThread().getId() + " Got Shutdown Queue Item - EXITING");
            break;
          }
        } catch (InterruptedException e) {
        }
      }
      
      LOG.info("Queue Thread:" + Thread.currentThread().getId() + " Released Semaphore");
      jobThreadSemaphore.release();
    } 
  }  
  

  /** 
   * Custom InputFormat that returns a set of splits via the (previously generated) splits manifest file 
   * @author rana
   *
   * @param <Key>
   * @param <Value>
   */
  public static class CheckpointInputFormat<Key,Value> extends SequenceFileInputFormat<Key, Value> {
    
    static Pattern splitPattern = Pattern.compile("^([^:]*)://([^:]*):([^+]*)\\+(.*)$");
    
    @Override
    public InputSplit[] getSplits(JobConf job, int numSplits)throws IOException {
      // get the checkpoint segment path ... 
      String segmentPath = job.get(SEGMENT_PATH_PROPERTY);
      // get the splits file ... 
      Path splitsPath = new Path(segmentPath,SPLITS_MANIFEST_FILE);
      // get fs 
      FileSystem fs = FileSystem.get(splitsPath.toUri(),job);
      // and read in splits ... 
      List<String> splits = textFileToList(fs, splitsPath);
      
      ArrayList<FileSplit> fileSplits = new ArrayList<FileSplit>(splits.size());
      
      // convert to FileSplits ... 
      for (String split : splits) {
        if (split.length() != 0 && !split.startsWith("#")) { 
          Matcher m = splitPattern.matcher(split);
          if (m.matches()) { 
            String sourceFilePath = m.group(1)+"://"+m.group(2);
            long offset = Long.parseLong(m.group(3));
            long length = Long.parseLong(m.group(4));
            
            fileSplits.add(new FileSplit(new Path(sourceFilePath),offset,length,(String[])null));
          }
          else { 
            throw new IOException("Failed to parse input split info:" + split);
          }
        }
      }
      return fileSplits.toArray(new FileSplit[0]);
    }
  }
  
  /** 
   * spawn a mapreduce job to parse a given checkpoint segment 
   * @param fs
   * @param conf
   * @param checkpointId
   * @param segmentDetail
   * @throws IOException
   */
  private static void parse(FileSystem fs,Configuration conf,long checkpointId,Pair<Long,Collection<SplitInfo>> segmentDetail)throws IOException { 
    
    // create segment path 
    Path fullyQualifiedSegmentPath = new Path(S3N_BUCKET_PREFIX + CHECKPOINT_STAGING_PATH +checkpointId+"/"+ segmentDetail.e0 +"/");
    
    // and derive output path 
    Path outputPath = new Path(fullyQualifiedSegmentPath,Constants.CHECKPOINT_JOB_OUTPUT_PATH);
    
    // delete the output if exists ... 
    fs.delete(outputPath, true);
    
    LOG.info("Starting Map-Reduce Job. SegmentId:" + segmentDetail.e0+ " OutputPath:" + outputPath);
    
    // run job...
    JobConf jobConf = new JobBuilder("parse job",conf)
      
      .input(fullyQualifiedSegmentPath) // TODO: HACK .. NOT NEEDED 
      .inputFormat(CheckpointInputFormat.class)
      .keyValue(Text.class, ParseOutput.class)
      .mapRunner(ParserMapRunner.class)
      .mapper(ParserMapper.class)
      // allow three attempts to process the split 
      .maxMapAttempts(3)
      .maxMapTaskFailures(1000)
      .speculativeExecution(true)
      .numReducers(0)
      .outputFormat(ParserOutputFormat.class)
      .output(outputPath)
      .reuseJVM(1000)
      .build();
    
    Path jobLogsPath = new Path(fullyQualifiedSegmentPath,Constants.CHECKPOINT_JOB_LOG_PATH);
    
    // delete if exists ... 
    fs.delete(jobLogsPath, true);
    
    jobConf.set("hadoop.job.history.user.location", jobLogsPath.toString());
    
    jobConf.set("fs.default.name", S3N_BUCKET_PREFIX);    
    jobConf.setLong("cc.segmet.id", segmentDetail.e0);
    // set task timeout to 120 minutes 
    jobConf.setInt("mapred.task.timeout", 20 * 60 * 1000);
    // set mapper runtime to max 2 hours .....  
    jobConf.setLong(ParserMapper.MAX_MAPPER_RUNTIME_PROPERTY, 120 * 60  * 1000);
    
    jobConf.setOutputCommitter(OutputCommitter.class);
    // allow lots of failures per tracker per job 
    jobConf.setMaxTaskFailuresPerTracker(Integer.MAX_VALUE);
        
    initializeTaskDataAwareJob(jobConf,segmentDetail.e0);

    JobClient.runJob(jobConf);
    
    finalizeJob(fs,conf,jobConf,segmentDetail.e0);

    
    // ok job execution was successful ... mark it so ... 
    Path successFile = new Path(fullyQualifiedSegmentPath,Constants.JOB_SUCCESS_FILE);
    
    fs.createNewFile(successFile);
    LOG.info("Map-Reduce Job for SegmentId:" + segmentDetail.e0+ " Completed Successfully");
  }
  
  /** 
   * Iterate previously parsed segments and collect partial and failed splits 
   * 
   * @param fs
   * @param lastCheckpointId
   * @throws IOException
   */
  
  static ArrayList<SegmentSplitDetail> iterateAvailableSegmentsCollectSplits(FileSystem fs,long lastCheckpointId)throws IOException {
    ArrayList<SegmentSplitDetail> listOut = new ArrayList<SegmentSplitDetail>();
    
    for (long segmentId : buildValidSegmentListGivenCheckpointId(fs, lastCheckpointId)) {
      System.out.println("Found Segment:" + segmentId);
      
      // get arc sizes by split upfront (because S3n wildcard operations are slow) 
      Multimap<Integer, Long> splitSizes= getArcFilesSizesSegment(fs,segmentId);
      
      System.out.println("Found ArcFiles for:" + splitSizes.keySet().size() + " Splits");
      
      // get failed and partial splits for segment 
      SortedSet<SegmentSplitDetail> allSplits = getAllSplits(fs, segmentId);
      SortedSet<SegmentSplitDetail> failedSplits = getFailedSplits(fs, segmentId);
      SortedSet<SegmentSplitDetail> partialSplits = getPartialSplits(fs, segmentId);
      
      // ok add all partial splits to list up front ... 
      listOut.addAll(partialSplits);
      
      // now calculate a raw to arc split ratio ... 
      DescriptiveStatistics stats = calculateArcToRawRatio(allSplits,failedSplits,partialSplits,splitSizes);
      double arcToRawRatio = stats.getMean();
      // calculate std-dev
      double stdDev = stats.getStandardDeviation();
      
      System.out.println("ArcToRaw Ratio:" + arcToRawRatio + " StdDev:" + stdDev);
      System.out.println("There are " + partialSplits.size() + " Partial splits");
      // exclude partial from failed to see how many actually failed ... 
      Sets.SetView<SegmentSplitDetail> reallyFailedSet = Sets.difference(failedSplits,partialSplits);
      System.out.println("There are " + reallyFailedSet.size() + " Failed splits");
      // walk each validating actual failure condidition
      for (SegmentSplitDetail split : reallyFailedSet) {
        // explicitly add failed list to list out ... 
        listOut.add(split);

        /**
        if (!splitSizes.containsKey(split.splitIndex)) {
          // add the failed split .. no questions asked ... 
          listOut.add(split);
        }
        else { 
          // ok otherwise ... get the arc sizes for the given split ... 
          Collection<Long> arcSizes = splitSizes.get(split.splitIndex);
          // iff 
          long totalArcSize = 0;
          for (long arcSize : splitSizes.get(split.splitIndex)) 
            totalArcSize += arcSize; 
          double itemRatio = (double) totalArcSize / (double) split.originalSplit.length;
          
          System.out.println("Failed Split: " 
          + split.splitIndex 
          + " has arc data:" 
          + splitSizes.get(split.splitIndex) 
          + " ItemRatio:"+ itemRatio + " Overall Ratio:" + arcToRawRatio);
        }
        **/
      }
    }
    return listOut;
  }
  
  /** 
   * arc to raw ratio calc (unused for now)
   * 
   * @param allSplits
   * @param failedSplits
   * @param partialSplits
   * @param arcSizes
   * @return
   */
  private static DescriptiveStatistics calculateArcToRawRatio(
      SortedSet<SegmentSplitDetail> allSplits,
      SortedSet<SegmentSplitDetail> failedSplits,
      SortedSet<SegmentSplitDetail> partialSplits,
      Multimap<Integer, Long> arcSizes) {
    
    DescriptiveStatistics stats = new DescriptiveStatistics();

    for (SegmentSplitDetail split : allSplits) { 
      if (!failedSplits.contains(split)  && !partialSplits.contains(split)) { 
        long totalArcSize = 0;
        for (long arcSize : arcSizes.get(split.splitIndex)) 
          totalArcSize += arcSize;
        if (totalArcSize != 0)
          stats.addValue((double)totalArcSize / (double)split.originalSplit.length); 
      }
    }
    
    return stats;
  }

  /** 
   * scan valid segments and pick up any whose id exceeds given last 
   * checkpoint id 
   * @param fs
   * @param lastCheckpointId
   * @return
   * @throws IOException
   */
  static Set<Long> buildValidSegmentListGivenCheckpointId(FileSystem fs,long lastCheckpointId)throws IOException { 
    return buildSegmentListGivenCheckpointId(fs, VALID_SEGMENTS_PATH, lastCheckpointId);
  }
  
  static Set<Long> buildSegmentListGivenCheckpointId(FileSystem fs,String validSegmentPath,long lastCheckpointId)throws IOException { 
    TreeSet<Long> validsegments = new TreeSet<Long>();
    for (FileStatus segmentStatus: fs.globStatus(new Path(validSegmentPath,"[0-9]*"))) {
      long segmentId = Long.parseLong(segmentStatus.getPath().getName());
      if (segmentId > lastCheckpointId) { 
        validsegments.add(segmentId);
      }
    }
    return validsegments;
  }

  

  static SortedSet<SegmentSplitDetail> getAllSplits(FileSystem fs,long segmentId)throws IOException { 
    return getSplitDetailsFromFile(fs,segmentId,new Path(VALID_SEGMENTS_PATH+Long.toString(segmentId)+"/"+SPLITS_MANIFEST_FILE),SPLITS_MANIFEST_FILE); 
  }

  static SortedSet<SegmentSplitDetail> getFailedSplits(FileSystem fs,long segmentId)throws IOException { 
    return getSplitDetailsFromFile(fs,segmentId,new Path(VALID_SEGMENTS_PATH+Long.toString(segmentId)+"/"+FAILED_SPLITS_MANIFEST_FILE),FAILED_SPLITS_MANIFEST_FILE); 
  }
  
  static SortedSet<SegmentSplitDetail> getPartialSplits(FileSystem fs,long segmentId)throws IOException { 
    return getSplitDetailsFromFile(fs,segmentId,new Path(VALID_SEGMENTS_PATH+Long.toString(segmentId)+"/"+TRAILING_SPLITS_MANIFEST_FILE),TRAILING_SPLITS_MANIFEST_FILE);
  }
  
  /** 
   * read split details given path and file type (partial, failed split etc.) 
   * 
   * @param fs
   * @param segmentId
   * @param path
   * @param splitType
   * @return
   * @throws IOException
   */
  static SortedSet<SegmentSplitDetail> getSplitDetailsFromFile(FileSystem fs,long segmentId,Path path,String splitType)throws IOException { 
        
    TreeSet<SegmentSplitDetail> splits = new TreeSet<EC2CheckpointTask.SegmentSplitDetail>();
    
    BufferedReader reader = new BufferedReader(new InputStreamReader(fs.open(path),Charset.forName("UTF-8")));
    try { 
      String line;
      int index=0;
      while ((line = reader.readLine()) != null) { 
        if (line.length() != 0 && !line.startsWith("#")) {  
          if (splitType == SPLITS_MANIFEST_FILE) { 
            SegmentSplitDetail splitDetail = new SegmentSplitDetail(segmentId);
            splitDetail.splitIndex = index++;
            splitDetail.originalSplit = new SplitInfo(line);
            splits.add(splitDetail);
          }
          else { 
            splits.add(splitDetailFromLogLine(segmentId,line, (splitType == TRAILING_SPLITS_MANIFEST_FILE)));
          }
        }
      }
    }
    finally { 
      reader.close();
    }
    return splits;
  }
  
  static Pattern partialSplitLogPattern = Pattern.compile("^([0-9]*),([^,]*),([^,]*)$");
  static Pattern failedSplitLogPattern = Pattern.compile("^([0-9]*),(.*)$");
  
  /** 
   * parse split detail given a split log line (based on type of split log)
   * @param segmentId
   * @param logLine
   * @param isPartialSplit
   * @return
   * @throws IOException
   */
  static SegmentSplitDetail splitDetailFromLogLine(long segmentId,String logLine,boolean isPartialSplit) throws IOException { 
    if (isPartialSplit) { 
      Matcher m = partialSplitLogPattern.matcher(logLine);
      if (m.matches() && m.groupCount() == 3) { 
        
        SegmentSplitDetail detail = new SegmentSplitDetail(segmentId);
        
        detail.splitIndex = Integer.parseInt(m.group(1));
        detail.partialSplit = new SplitInfo(m.group(2));
        detail.originalSplit = new SplitInfo(m.group(3));
        
        return detail;

      }
      else { 
        throw new IOException("Invalid Split Info:" + logLine);
      }
    }
    else { 
      Matcher m = failedSplitLogPattern.matcher(logLine);

      if (m.matches() && m.groupCount() == 2) { 
        
        SegmentSplitDetail detail = new SegmentSplitDetail(segmentId);
        
        detail.splitIndex = Integer.parseInt(m.group(1));
        detail.originalSplit = new SplitInfo(m.group(2));
        
        return detail;
      }
      else { 
        throw new IOException("Invalid Split Info:" + logLine);
      }
    }
  }
  
  
  /** 
   * Helper class than encapsulated a single file split's details
   * TODO: WHY ARE WE NOT EXTENDING FileSplit ???? 
   * @author rana
   *
   */
  static class SplitInfo implements Comparable<SplitInfo> { 
    
    String  sourceFilePath;
    long    offset;
    long    length;
    
    static Pattern pattern = Pattern.compile("^([^:]*)://([^:]*):([^+]*)\\+(.*)$");
    
    SplitInfo(String splitText)throws IOException { 
      Matcher m = pattern.matcher(splitText);
      if (m.matches() && m.groupCount() == 4) { 
        sourceFilePath = m.group(1)+"://"+m.group(2);
        offset = Long.parseLong(m.group(3));
        length = Long.parseLong(m.group(4));
      }
      else { 
        throw new IOException("Invalid Split:"+ splitText);
      }
    }

    @Override
    public int compareTo(SplitInfo other) {
      int result = sourceFilePath.compareTo(other.sourceFilePath);
      if (result == 0) { 
        result = (offset < other.offset) ? -1 : (offset > other.offset) ? 1: 0;
      }
      return result;
    }
    
    @Override
    public String toString() {
      return sourceFilePath+":"+offset+"+"+length;
    }
  }
  
  /** 
   * A class representing a partially processed or unprocessed split
   *  
   * @author rana
   *
   */
  static class SegmentSplitDetail implements Comparable<SegmentSplitDetail>{

    long    segmentId;
    int     splitIndex;
    SplitInfo originalSplit;
    SplitInfo partialSplit;    
    
    public SegmentSplitDetail(long segmentId) { 
      this.segmentId = segmentId;
    }
   
    
    @Override
    public int compareTo(SegmentSplitDetail o) {
      return (splitIndex < o.splitIndex) ? -1: (splitIndex > o.splitIndex) ? 1: 0;
    }
    
    public boolean isPartialSplit() { 
      return partialSplit != null;
    }
    
    @Override
    public String toString() {
      return Long.toString(segmentId)
          +","
          +splitIndex
          +","
          +((isPartialSplit()) ? "P":"F") 
          + "," 
          + (isPartialSplit() ? partialSplit.toString() : originalSplit.toString());
    }
  }
  
}
