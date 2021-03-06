package tachyon;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.apache.thrift.TException;
import org.apache.log4j.Logger;
import org.apache.commons.io.FileUtils;

import tachyon.conf.CommonConf;
import tachyon.conf.WorkerConf;
import tachyon.thrift.Command;
import tachyon.thrift.FailedToCheckpointException;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.NetAddress;
import tachyon.thrift.SuspectedFileSizeException;
import tachyon.thrift.WorkerService;

/**
 * <code>WorkerServiceHandler</code> handles all the RPC call to the worker.
 */
public class WorkerServiceHandler implements WorkerService.Iface {
  public final BlockingQueue<Integer> sDataAccessQueue = 
      new ArrayBlockingQueue<Integer>(Constants.WORKER_FILES_QUEUE_SIZE);

  private final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  private final CommonConf COMMON_CONF;

  private volatile MasterClient mMasterClient;
  private InetSocketAddress mMasterAddress;
  private WorkerInfo mWorkerInfo;

  // TODO Should merge these three structures, and make it more clean. Define NodeStroage class.
  private Set<Integer> mMemoryData = new HashSet<Integer>();
  private Map<Integer, Long> mLatestFileAccessTimeMs = new HashMap<Integer, Long>();
  private Map<Integer, Set<Long>> mUsersPerLockedFile = new HashMap<Integer, Set<Long>>();
  private Map<Long, Set<Integer>> mLockedFilesPerUser = new HashMap<Long, Set<Integer>>();
  private Map<Integer, Long> mFileSizes = new HashMap<Integer, Long>();
  private BlockingQueue<Integer> mRemovedFileList = 
      new ArrayBlockingQueue<Integer>(Constants.WORKER_FILES_QUEUE_SIZE);
  private BlockingQueue<Integer> mAddedFileList = 
      new ArrayBlockingQueue<Integer>(Constants.WORKER_FILES_QUEUE_SIZE);

  private File mDataFolder;
  private File mUserFolder;
  private String mUnderfsWorkerFolder;
  private UnderFileSystem mUnderFs;

  private Users mUsers;

  public WorkerServiceHandler(InetSocketAddress masterAddress, InetSocketAddress workerAddress,
      String dataFolder, long spaceLimitBytes) {
    COMMON_CONF = CommonConf.get();

    mMasterAddress = masterAddress;
    mMasterClient = new MasterClient(mMasterAddress);

    long id = 0;
    while (id == 0) {
      try {
        mMasterClient.open();
        id = mMasterClient.worker_register(
            new NetAddress(workerAddress.getHostName(), workerAddress.getPort()),
            spaceLimitBytes, 0, new ArrayList<Integer>());
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        id = 0;
        CommonUtils.sleepMs(LOG, 1000);
      }
    }

    mDataFolder = new File(dataFolder);
    mUserFolder = new File(mDataFolder.toString(), WorkerConf.get().USER_TEMP_RELATIVE_FOLDER);
    mWorkerInfo = new WorkerInfo(id, workerAddress, spaceLimitBytes);
    mUnderfsWorkerFolder = COMMON_CONF.WORKERS_FOLDER + "/" + id;
    mUnderFs = UnderFileSystem.getUnderFileSystem(COMMON_CONF.UNDERFS_ADDRESS);
    mUsers = new Users(mUserFolder.toString(), mUnderfsWorkerFolder);

    try {
      initializeWorkerInfo();
    } catch (FileDoesNotExistException e) {
      CommonUtils.runtimeException(e);
    } catch (SuspectedFileSizeException e) {
      CommonUtils.runtimeException(e);
    } catch (TException e) {
      CommonUtils.runtimeException(e);
    }

    LOG.info("Current Worker Info: " + mWorkerInfo);
  }

  @Override
  public void accessFile(int fileId) throws TException {
    sDataAccessQueue.add(fileId);
  }

  @Override
  public void addCheckpoint(long userId, int fileId)
      throws FileDoesNotExistException, SuspectedFileSizeException, 
      FailedToCheckpointException, TException {
    // TODO This part need to be changed.
    String srcPath = getUserUnderfsTempFolder(userId) + "/" + fileId;
    String dstPath = COMMON_CONF.DATA_FOLDER + "/" + fileId;
    try {
      if (!mUnderFs.rename(srcPath, dstPath)) {
        throw new FailedToCheckpointException("Failed to rename " + srcPath + " to " + dstPath);
      }
    } catch (IOException e1) {
      throw new FailedToCheckpointException("Failed to rename " + srcPath + " to " + dstPath);
    }
    long fileSize;
    try {
      fileSize = mUnderFs.getFileSize(dstPath);
    } catch (IOException e) {
      throw new FailedToCheckpointException("Failed to getFileSize " + dstPath);
    }
    mMasterClient.addCheckpoint(mWorkerInfo.getId(), fileId, fileSize, dstPath);
  }

  private void addFoundPartition(int fileId, long fileSizeBytes)
      throws FileDoesNotExistException, SuspectedFileSizeException, TException {
    addId(fileId, fileSizeBytes);
    mMasterClient.worker_cachedFile(mWorkerInfo.getId(), mWorkerInfo.getUsedBytes(), fileId,
        fileSizeBytes);
  }

  private void addId(int fileId, long fileSizeBytes) {
    mWorkerInfo.updateFile(true, fileId);

    synchronized (mLatestFileAccessTimeMs) {
      mLatestFileAccessTimeMs.put(fileId, System.currentTimeMillis());
      mFileSizes.put(fileId, fileSizeBytes);
      mMemoryData.add(fileId);
    }
  }

  @Override
  public void cacheFile(long userId, int fileId)
      throws FileDoesNotExistException, SuspectedFileSizeException, TException {
    File srcFile = new File(getUserTempFolder(userId) + "/" + fileId);
    File dstFile = new File(mDataFolder + "/" + fileId);
    long fileSizeBytes = srcFile.length(); 
    if (!srcFile.exists()) {
      throw new FileDoesNotExistException("File " + srcFile + " does not exist.");
    }
    if (!srcFile.renameTo(dstFile)) {
      throw new FileDoesNotExistException("Failed to rename file from " + srcFile.getPath() +
          " to " + dstFile.getPath());
    }
    addId(fileId, fileSizeBytes);
    mUsers.addOwnBytes(userId, - fileSizeBytes);
    mMasterClient.worker_cachedFile(mWorkerInfo.getId(), 
        mWorkerInfo.getUsedBytes(), fileId, fileSizeBytes);
  }

  public void checkStatus() {
    List<Long> removedUsers = mUsers.checkStatus(mWorkerInfo);

    for (long userId : removedUsers) {
      synchronized (mUsersPerLockedFile) {
        Set<Integer> fileIds = mLockedFilesPerUser.get(userId);
        mLockedFilesPerUser.remove(userId);
        if (fileIds != null) {
          for (int fileId : fileIds) {
            try {
              unlockFile(fileId, userId);
            } catch (TException e) {
              CommonUtils.runtimeException(e);
            }
          }
        }
      }
    }

    synchronized (mLatestFileAccessTimeMs) {
      while (!sDataAccessQueue.isEmpty()) {
        int fileId = sDataAccessQueue.poll();

        mLatestFileAccessTimeMs.put(fileId, System.currentTimeMillis());
      }
    }
  }

  private void freeFile(int fileId) {
    mWorkerInfo.returnUsedBytes(mFileSizes.get(fileId));
    mWorkerInfo.removeFile(fileId);
    File srcFile = new File(mDataFolder + "/" + fileId);
    srcFile.delete();
    synchronized (mLatestFileAccessTimeMs) {
      mLatestFileAccessTimeMs.remove(fileId);
      mFileSizes.remove(fileId);
      mRemovedFileList.add(fileId);
      mMemoryData.remove(fileId);
    }
    LOG.info("Removed Data " + fileId);
  }

  @Override
  public String getDataFolder() throws TException {
    return mDataFolder.toString();
  }

  @Override
  public String getUserTempFolder(long userId) throws TException {
    String ret = mUsers.getUserTempFolder(userId);
    LOG.info("Return UserTempFolder for " + userId + " : " + ret);
    return ret;
  }

  @Override
  public String getUserUnderfsTempFolder(long userId) throws TException {
    String ret = mUsers.getUserHdfsTempFolder(userId);
    LOG.info("Return UserHdfsTempFolder for " + userId + " : " + ret);
    return ret;
  }

  public Command heartbeat() throws TException {
    ArrayList<Integer> sendRemovedPartitionList = new ArrayList<Integer>();
    while (mRemovedFileList.size() > 0) {
      sendRemovedPartitionList.add(mRemovedFileList.poll());
    }
    return mMasterClient.worker_heartbeat(mWorkerInfo.getId(), mWorkerInfo.getUsedBytes(),
        sendRemovedPartitionList);
  }

  private void initializeWorkerInfo() 
      throws FileDoesNotExistException, SuspectedFileSizeException, TException {
    LOG.info("Initializing the worker info.");
    if (!mDataFolder.exists()) {
      LOG.info("Local folder " + mDataFolder.toString() + " does not exist. Creating a new one.");

      mDataFolder.mkdir();
      mUserFolder.mkdir();

      return;
    }

    if (!mDataFolder.isDirectory()) {
      String tmp = mDataFolder.toString() + " is not a folder!";
      LOG.error(tmp);
      throw new IllegalArgumentException(tmp);
    }

    int cnt = 0;
    for (File tFile : mDataFolder.listFiles()) {
      if (tFile.isFile()) {
        cnt ++;
        LOG.info("File " + cnt + ": " + tFile.getPath() + " with size " + tFile.length() + " Bs.");

        int fileId = CommonUtils.getFileIdFromFileName(tFile.getName());
        boolean success = mWorkerInfo.requestSpaceBytes(tFile.length());
        addFoundPartition(fileId, tFile.length());
        mAddedFileList.add(fileId);
        if (!success) {
          CommonUtils.runtimeException("Pre-existing files exceed the local memory capacity.");
        }
      }
    }

    if (mUserFolder.exists()) {
      try {
        FileUtils.deleteDirectory(mUserFolder);
      } catch (IOException e) {
        LOG.error(e.getMessage(), e);
      }
    }
    mUserFolder.mkdir();
  }

  @Override
  public void lockFile(int fileId, long userId) throws TException {
    synchronized (mUsersPerLockedFile) {
      if (!mUsersPerLockedFile.containsKey(fileId)) {
        mUsersPerLockedFile.put(fileId, new HashSet<Long>());
      }
      mUsersPerLockedFile.get(fileId).add(userId);

      if (!mLockedFilesPerUser.containsKey(userId)) {
        mLockedFilesPerUser.put(userId, new HashSet<Integer>());
      }
      mLockedFilesPerUser.get(userId).add(fileId);
    }
  }

  private boolean memoryEvictionLRU() {
    long latestTimeMs = Long.MAX_VALUE;
    int fileId = -1;
    Set<Integer> pinList = new HashSet<Integer>();

    // TODO Cache replacement policy should go through Master.
    try {
      pinList = mMasterClient.worker_getPinIdList();
    } catch (TException e) {
      LOG.error(e.getMessage());
      pinList = new HashSet<Integer>();
    }

    synchronized (mLatestFileAccessTimeMs) {
      synchronized (mUsersPerLockedFile) {
        for (Entry<Integer, Long> entry : mLatestFileAccessTimeMs.entrySet()) {
          if (entry.getValue() < latestTimeMs && !pinList.contains(entry.getKey())) {
            if(!mUsersPerLockedFile.containsKey(entry.getKey())) {
              fileId = entry.getKey();
              latestTimeMs = entry.getValue();
            }
          }
        }
        if (fileId != -1) {
          freeFile(fileId);
          return true;
        }
      }
    }

    return false;
  }

  public void register() {
    long id = 0;
    while (id == 0) {
      try {
        mMasterClient.open();
        id = mMasterClient.worker_register(
            new NetAddress(mWorkerInfo.ADDRESS.getHostName(), mWorkerInfo.ADDRESS.getPort()),
            mWorkerInfo.getCapacityBytes(), 0, new ArrayList<Integer>(mMemoryData));
      } catch (TException e) {
        LOG.error(e.getMessage(), e);
        id = 0;
        CommonUtils.sleepMs(LOG, 1000);
      }
    }
    mWorkerInfo.updateId(id);
  }

  @Override
  public void returnSpace(long userId, long returnedBytes) throws TException {
    long preAvailableBytes = mWorkerInfo.getAvailableBytes();
    if (returnedBytes > mUsers.ownBytes(userId)) {
      LOG.error("User " + userId + " does not own " + returnedBytes + " bytes.");
    } else {
      mWorkerInfo.returnUsedBytes(returnedBytes);
      mUsers.addOwnBytes(userId, - returnedBytes);
    }

    LOG.info("returnSpace(" + userId + ", " + returnedBytes + ") : " +
        preAvailableBytes + " returned: " + returnedBytes + ". New Available: " +
        mWorkerInfo.getAvailableBytes());
  }

  @Override
  public boolean requestSpace(long userId, long requestBytes) throws TException {
    LOG.info("requestSpace(" + userId + ", " + requestBytes + "): Current available: " +
        mWorkerInfo.getAvailableBytes() + " requested: " + requestBytes);
    if (mWorkerInfo.getCapacityBytes() < requestBytes) {
      LOG.info("user_requestSpace(): requested memory size is larger than the total memory on" +
          " the machine.");
      return false;
    }

    while (!mWorkerInfo.requestSpaceBytes(requestBytes)) {
      if (!memoryEvictionLRU()) {
        return false;
      }
    }

    mUsers.addOwnBytes(userId, requestBytes);

    return true;
  }

  public void resetMasterClient() {
    MasterClient tMasterClient = new MasterClient(mMasterAddress);
    tMasterClient.open();
    mMasterClient = tMasterClient;
  }

  @Override
  public void unlockFile(int fileId, long userId) throws TException {
    synchronized (mUsersPerLockedFile) {
      if (mUsersPerLockedFile.containsKey(fileId)) {
        mUsersPerLockedFile.get(fileId).remove(userId);
        if (mUsersPerLockedFile.get(fileId).size() == 0) {
          mUsersPerLockedFile.remove(fileId);
        }
      }

      if (mLockedFilesPerUser.containsKey(userId)) {
        mLockedFilesPerUser.get(userId).remove(fileId);
      }
    }
  }

  @Override
  public void userHeartbeat(long userId) throws TException {
    mUsers.userHeartbeat(userId);
  }
}