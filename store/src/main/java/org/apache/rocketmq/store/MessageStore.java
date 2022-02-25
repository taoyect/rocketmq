/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.store;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.rocketmq.common.SystemClock;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.store.config.BrokerRole;
import org.apache.rocketmq.store.config.MessageStoreConfig;
import org.apache.rocketmq.store.ha.HAService;
import org.apache.rocketmq.store.logfile.MappedFile;
import org.apache.rocketmq.store.queue.ConsumeQueueInterface;
import org.apache.rocketmq.store.queue.ConsumeQueueStore;
import org.apache.rocketmq.store.schedule.ScheduleMessageService;
import org.apache.rocketmq.store.stats.BrokerStatsManager;
import org.apache.rocketmq.store.util.PerfCounter;

/**
 * This class defines contracting interfaces to implement, allowing third-party vendor to use customized message store.
 */
public interface MessageStore {

    /**
     * Load previously stored messages.
     *
     * @return true if success; false otherwise.
     */
    boolean load();

    /**
     * Launch this message store.
     *
     * @throws Exception if there is any error.
     */
    void start() throws Exception;

    /**
     * Shutdown this message store.
     */
    void shutdown();

    /**
     * Destroy this message store. Generally, all persistent files should be removed after invocation.
     */
    void destroy();

    /** Store a message into store in async manner, the processor can process the next request
     *  rather than wait for result
     *  when result is completed, notify the client in async manner
     *
     * @param msg MessageInstance to store
     * @return a CompletableFuture for the result of store operation
     */
    default CompletableFuture<PutMessageResult> asyncPutMessage(final MessageExtBrokerInner msg) {
        return CompletableFuture.completedFuture(putMessage(msg));
    }

    /**
     * Store a batch of messages in async manner
     * @param messageExtBatch the message batch
     * @return a CompletableFuture for the result of store operation
     */
    default CompletableFuture<PutMessageResult> asyncPutMessages(final MessageExtBatch messageExtBatch) {
        return CompletableFuture.completedFuture(putMessages(messageExtBatch));
    }

    /**
     * Store a message into store.
     *
     * @param msg Message instance to store
     * @return result of store operation.
     */
    PutMessageResult putMessage(final MessageExtBrokerInner msg);

    /**
     * Store a batch of messages.
     *
     * @param messageExtBatch Message batch.
     * @return result of storing batch messages.
     */
    PutMessageResult putMessages(final MessageExtBatch messageExtBatch);

    /**
     * Query at most <code>maxMsgNums</code> messages belonging to <code>topic</code> at <code>queueId</code> starting
     * from given <code>offset</code>. Resulting messages will further be screened using provided message filter.
     *
     * @param group Consumer group that launches this query.
     * @param topic Topic to query.
     * @param queueId Queue ID to query.
     * @param offset Logical offset to start from.
     * @param maxMsgNums Maximum count of messages to query.
     * @param messageFilter Message filter used to screen desired messages.
     * @return Matched messages.
     */
    GetMessageResult getMessage(final String group, final String topic, final int queueId,
        final long offset, final int maxMsgNums, final MessageFilter messageFilter);

    /**
     * Query at most <code>maxMsgNums</code> messages belonging to <code>topic</code> at <code>queueId</code> starting
     * from given <code>offset</code>. Resulting messages will further be screened using provided message filter.
     *
     * @param group Consumer group that launches this query.
     * @param topic Topic to query.
     * @param queueId Queue ID to query.
     * @param offset Logical offset to start from.
     * @param maxMsgNums Maximum count of messages to query.
     * @param maxTotalMsgSize Maxisum total msg size of the messages
     * @param messageFilter Message filter used to screen desired messages.
     * @return Matched messages.
     */
    GetMessageResult getMessage(final String group, final String topic, final int queueId,
        final long offset, final int maxMsgNums, final int maxTotalMsgSize, final MessageFilter messageFilter);

    /**
     * Get maximum offset of the topic queue.
     *
     * @param topic Topic name.
     * @param queueId Queue ID.
     * @return Maximum offset at present.
     */
    long getMaxOffsetInQueue(final String topic, final int queueId);

    /**
     * Get maximum offset of the topic queue.
     *
     * @param topic Topic name.
     * @param queueId Queue ID.
     * @param committed If only count committed
     * @return Maximum offset at present.
     */
    long getMaxOffsetInQueue(final String topic, final int queueId, final boolean committed);

    /**
     * Get the minimum offset of the topic queue.
     *
     * @param topic Topic name.
     * @param queueId Queue ID.
     * @return Minimum offset at present.
     */
    long getMinOffsetInQueue(final String topic, final int queueId);

    /**
     * Get the offset of the message in the commit log, which is also known as physical offset.
     *
     * @param topic Topic of the message to lookup.
     * @param queueId Queue ID.
     * @param consumeQueueOffset offset of consume queue.
     * @return physical offset.
     */
    long getCommitLogOffsetInQueue(final String topic, final int queueId, final long consumeQueueOffset);

    /**
     * Look up the physical offset of the message whose store timestamp is as specified.
     *
     * @param topic Topic of the message.
     * @param queueId Queue ID.
     * @param timestamp Timestamp to look up.
     * @return physical offset which matches.
     */
    long getOffsetInQueueByTime(final String topic, final int queueId, final long timestamp);

    /**
     * Look up the message by given commit log offset.
     *
     * @param commitLogOffset physical offset.
     * @return Message whose physical offset is as specified.
     */
    MessageExt lookMessageByOffset(final long commitLogOffset);

    /**
     * Look up the message by given commit log offset and size.
     *
     * @param commitLogOffset physical offset.
     * @param size message size
     * @return Message whose physical offset is as specified.
     */
    MessageExt lookMessageByOffset(long commitLogOffset, int size);

    /**
     * Get one message from the specified commit log offset.
     *
     * @param commitLogOffset commit log offset.
     * @return wrapped result of the message.
     */
    SelectMappedBufferResult selectOneMessageByOffset(final long commitLogOffset);

    /**
     * Get one message from the specified commit log offset.
     *
     * @param commitLogOffset commit log offset.
     * @param msgSize message size.
     * @return wrapped result of the message.
     */
    SelectMappedBufferResult selectOneMessageByOffset(final long commitLogOffset, final int msgSize);

    /**
     * Get the running information of this store.
     *
     * @return message store running info.
     */
    String getRunningDataInfo();

    /**
     * Message store runtime information, which should generally contains various statistical information.
     *
     * @return runtime information of the message store in format of key-value pairs.
     */
    HashMap<String, String> getRuntimeInfo();

    /**
     * Get the maximum commit log offset.
     *
     * @return maximum commit log offset.
     */
    long getMaxPhyOffset();

    /**
     * Get the minimum commit log offset.
     *
     * @return minimum commit log offset.
     */
    long getMinPhyOffset();

    /**
     * Get the store time of the earliest message in the given queue.
     *
     * @param topic Topic of the messages to query.
     * @param queueId Queue ID to find.
     * @return store time of the earliest message.
     */
    long getEarliestMessageTime(final String topic, final int queueId);

    /**
     * Get the store time of the earliest message in this store.
     *
     * @return timestamp of the earliest message in this store.
     */
    long getEarliestMessageTime();

    /**
     * Get the store time of the message specified.
     *
     * @param topic message topic.
     * @param queueId queue ID.
     * @param consumeQueueOffset consume queue offset.
     * @return store timestamp of the message.
     */
    long getMessageStoreTimeStamp(final String topic, final int queueId, final long consumeQueueOffset);

    /**
     * Get the total number of the messages in the specified queue.
     *
     * @param topic Topic
     * @param queueId Queue ID.
     * @return total number.
     */
    long getMessageTotalInQueue(final String topic, final int queueId);

    /**
     * Get the raw commit log data starting from the given offset, which should used for replication purpose.
     *
     * @param offset starting offset.
     * @return commit log data.
     */
    SelectMappedBufferResult getCommitLogData(final long offset);

    /**
     * Append data to commit log.
     *
     * @param startOffset starting offset.
     * @param data data to append.
     * @param dataStart the start index of data array
     * @param dataLength the length of data array
     * @return true if success; false otherwise.
     */
    boolean appendToCommitLog(final long startOffset, final byte[] data, int dataStart, int dataLength);

    /**
     * Execute file deletion manually.
     */
    void executeDeleteFilesManually();

    /**
     * Query messages by given key.
     *
     * @param topic topic of the message.
     * @param key message key.
     * @param maxNum maximum number of the messages possible.
     * @param begin begin timestamp.
     * @param end end timestamp.
     */
    QueryMessageResult queryMessage(final String topic, final String key, final int maxNum, final long begin,
        final long end);

    /**
     * Update HA master address.
     *
     * @param newAddr new address.
     */
    void updateHaMasterAddress(final String newAddr);

    /**
     * Return how much the slave falls behind.
     *
     * @return number of bytes that slave falls behind.
     */
    long slaveFallBehindMuch();

    /**
     * Return the current timestamp of the store.
     *
     * @return current time in milliseconds since 1970-01-01.
     */
    long now();

    /**
     * Clean unused topics.
     *
     * @param topics all valid topics.
     * @return number of the topics deleted.
     */
    int cleanUnusedTopic(final Set<String> topics);

    /**
     * Clean expired consume queues.
     */
    void cleanExpiredConsumerQueue();

    /**
     * Check if the given message has been swapped out of the memory.
     *
     * @param topic topic.
     * @param queueId queue ID.
     * @param consumeOffset consume queue offset.
     * @return true if the message is no longer in memory; false otherwise.
     */
    boolean checkInDiskByConsumeOffset(final String topic, final int queueId, long consumeOffset);

    /**
     * Get number of the bytes that have been stored in commit log and not yet dispatched to consume queue.
     *
     * @return number of the bytes to dispatch.
     */
    long dispatchBehindBytes();

    /**
     * Flush the message store to persist all data.
     *
     * @return maximum offset flushed to persistent storage device.
     */
    long flush();

    /**
     * Reset written offset.
     *
     * @param phyOffset new offset.
     * @return true if success; false otherwise.
     */
    boolean resetWriteOffset(long phyOffset);

    /**
     * Get confirm offset.
     *
     * @return confirm offset.
     */
    long getConfirmOffset();

    /**
     * Set confirm offset.
     *
     * @param phyOffset confirm offset to set.
     */
    void setConfirmOffset(long phyOffset);

    /**
     * Check if the operation system page cache is busy or not.
     *
     * @return true if the OS page cache is busy; false otherwise.
     */
    boolean isOSPageCacheBusy();

    /**
     * Get lock time in milliseconds of the store by far.
     *
     * @return lock time in milliseconds.
     */
    long lockTimeMills();

    /**
     * Check if the transient store pool is deficient.
     *
     * @return true if the transient store pool is running out; false otherwise.
     */
    boolean isTransientStorePoolDeficient();

    /**
     * Get the dispatcher list.
     *
     * @return list of the dispatcher.
     */
    LinkedList<CommitLogDispatcher> getDispatcherList();

    /**
     * Get consume queue of the topic/queue.
     *
     * @param topic Topic.
     * @param queueId Queue ID.
     * @return Consume queue.
     */
    ConsumeQueueInterface getConsumeQueue(String topic, int queueId);

    ScheduleMessageService getScheduleMessageService();

    /**
     * Get BrokerStatsManager of the messageStore.
     *
     * @return BrokerStatsManager.
     */
    BrokerStatsManager getBrokerStatsManager();

    /**
     * handle
     * @param brokerRole
     */
    void handleScheduleMessageService(BrokerRole brokerRole);

    /**
     * Will be triggered when a new message is appended to commit log.
     * @param msg the msg that is appended to commit log
     * @param result append message result
     * @param commitLogFile commit log file
     */
    void onCommitLogAppend(MessageExtBrokerInner msg, AppendMessageResult result, MappedFile commitLogFile);

    /**
     * Will be triggered when a new dispatch request is sent to message store.
     * @param dispatchRequest dispatch request
     * @param doDispatch do dispatch if true
     * @param commitLogFile commit log file
     * @param isRecover is from recover process
     * @param isFileEnd if the dispatch request represents 'file end'
     */
    void onCommitLogDispatch(DispatchRequest dispatchRequest, boolean doDispatch, MappedFile commitLogFile, boolean isRecover, boolean isFileEnd);

    /**
     * Get the message store config
     * @return the message store config
     */
    MessageStoreConfig getMessageStoreConfig();

    /**
     * Get the statistics service
     * @return the statistics service
     */
    StoreStatsService getStoreStatsService();

    /**
     * Get the store checkpoint component
     * @return the checkpoint component
     */
    StoreCheckpoint getStoreCheckpoint();

    /**
     * Get the system clock
     * @return the system clock
     */
    SystemClock getSystemClock();

    /**
     * Get the commit log
     * @return the commit log
     */
    CommitLog getCommitLog();

    /**
     * Get running flags
     * @return running flags
     */
    RunningFlags getRunningFlags();

    /**
     * Get the transient store pool
     * @return the transient store pool
     */
    TransientStorePool getTransientStorePool();

    /**
     * Get the HA service
     * @return the HA service
     */
    HAService getHaService();

    /**
     * Register clean file hook
     * @param logicalQueueCleanHook logical queue clean hook
     */
    void registerCleanFileHook(CleanFilesHook logicalQueueCleanHook);

    /**
     * Get the allocate-mappedFile service
     * @return the allocate-mappedFile service
     */
    AllocateMappedFileService getAllocateMappedFileService();

    /**
     * Truncate dirty logic files
     * @param phyOffset physical offset
     */
    void truncateDirtyLogicFiles(long phyOffset);

    /**
     * Destroy logics files
     */
    void destroyLogics();

    /**
     * Unlock mappedFile
     * @param unlockMappedFile the file that needs to be unlocked
     */
    void unlockMappedFile(MappedFile unlockMappedFile);

    /**
     * Get the perf counter component
     * @return the perf counter component
     */
    PerfCounter.Ticks getPerfCounter();

    /**
     * Get the queue store
     * @return the queue store
     */
    ConsumeQueueStore getQueueStore();

    /**
     * If 'sync disk flush' is configured in this message store
     * @return yes if true, no if false
     */
    boolean isSyncDiskFlush();

    /**
     * If this message store is sync master role
     * @return yes if true, no if false
     */
    boolean isSyncMaster();

    /**
     * Assign an queue offset and increase it.
     * If there is a race condition, you need to lock/unlock this method yourself.
     *
     * @param topicQueueKey topic-queue key
     * @param msg message
     * @param messageNum message num
     */
    void assignOffset(String topicQueueKey, MessageExtBrokerInner msg, short messageNum);

    /**
     * get topic config
     * @param topic topic name
     * @return topic config info
     */
    Optional<TopicConfig> getTopicConfig(String topic);

    /**
     * Clean unused lmq topic.
     * When calling to clean up the lmq topic,
     * the lmq topic cannot be used to write messages at the same time,
     * otherwise the messages of the cleaning lmq topic may be lost,
     * please call this method with caution
     * @param topic lmq topic
     */
    void cleanUnusedLmqTopic(String topic);
}
