package com.unistack.tamboo.message.kafka.util;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.unistack.tamboo.commons.utils.errors.GeneralServiceException;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.FutureTask;

import static com.unistack.tamboo.commons.utils.CommonUtils.getSecurityProps;

/**
 * @author Gyges Zean
 * @date 2018/7/12
 */
public class OffsetOperator {

    private static  Logger logger = LoggerFactory.getLogger(OffsetOperator.class);

    private static Properties consumerConfigs = new Properties();

    /**
     * 消费组id
     */
    private static  String GROUP = "system";
    /**
     * session超时时间
     */
    private static  String SESSION_TIMEOUT_MS = "30000";
    /**
     * 阻塞时间
     */
    private static  Long POLL_TIMEOUT_MS = 50L;


    /**
     * 构建consumer实例
     *
     * @return
     */
    private static KafkaConsumer<byte[], byte[]> createConsumer(String bootstrapServers) {
        consumerConfigs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerConfigs.put(ConsumerConfig.GROUP_ID_CONFIG, GROUP);
        consumerConfigs.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MS);
        consumerConfigs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumerConfigs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerConfigs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        consumerConfigs.putAll(getSecurityProps(bootstrapServers));
        return new KafkaConsumer<>(consumerConfigs);
    }


    /**
     * 获取JSON数据
     *
     * @param timestamp
     * @param bootstrapServers
     * @param topic
     * @return
     */
    public static List<String> recordJSONValue(List<Long> timestamp, String bootstrapServers, String topic) throws GeneralServiceException {
        List<Long> offsets = getOffsetsByTimestamp(timestamp, bootstrapServers, topic);
        List<String> result = Lists.newArrayList();
        for (Map<Object, byte[]> record : getRecordValueByOffset(offsets, bootstrapServers, topic)) {
            for (Object key : record.keySet()) {
                String value = new String(record.get(key));
                result.add(value);
            }
        }

        return result;
    }


    /**
     * 通过timestamp获取offsets
     *
     * @param timestamps
     * @param bootstrapServers
     * @param topic
     * @return
     */
    public static List<Long> getOffsetsByTimestamp(List<Long> timestamps, String bootstrapServers, String topic) {
        List<Long> data = Lists.newArrayList();
        KafkaConsumer<byte[], byte[]> consumer = createConsumer(bootstrapServers);

        for (Long timestamp : timestamps) {
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);

            for (PartitionInfo partition : partitionInfos) {
                TopicPartition topicPartition = new TopicPartition(partition.topic(), partition.partition());
                Map<TopicPartition, Long> timestampsToSearch = Maps.newHashMap();
                timestampsToSearch.put(topicPartition, timestamp);
                Map<TopicPartition, OffsetAndTimestamp> offsetsForTimes = consumer.offsetsForTimes(timestampsToSearch);
                for (Map.Entry<TopicPartition, OffsetAndTimestamp> entry : offsetsForTimes.entrySet()) {
                    OffsetAndTimestamp offsetAndTimestamp = entry.getValue();
                    if (!Objects.isNull(offsetAndTimestamp)) {
                        data.add(offsetAndTimestamp.offset());
                    }
                }
            }
        }
        return data;
    }


    /**
     * 获取top5的offsets
     *
     * @param topic
     * @param bootstrapServers
     * @return
     */
    public static List<byte[]> getTop5Records(int size, String topic, String bootstrapServers) {
        List<byte[]> records = Lists.newArrayList();
        KafkaConsumer<byte[], byte[]> consumer = null;
        try {
            consumer = createConsumer(bootstrapServers);
            List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
            for (PartitionInfo partitionInfo : partitionInfos) {
                TopicPartition topicPartition = new TopicPartition(partitionInfo.topic(), partitionInfo.partition());
                consumer.assign(Collections.singleton(topicPartition));
                consumer.seekToBeginning(Collections.singleton(topicPartition));
                if (Objects.isNull(consumer.committed(topicPartition))) {
                    break;
                }
                logger.info("assignment topicPartition is -> " + consumer.assignment().toString());
                flag:
                while (true) {
                    ConsumerRecords<byte[], byte[]> consumerRecords = consumer.poll(0);
                    int count = 0;
                    for (ConsumerRecord record : consumerRecords) {
                        count++;
                        records.add((byte[]) record.value());
                        if (count == size) {
                            break flag;
                        }
                    }
                }
                consumer.commitSync();
            }
        } finally {
            if (consumer != null) {
                consumer.close();
            }
        }
        return records;
    }


    /**
     * 根据offset获取数据
     *
     * @param offsets
     * @param bootstrapServers
     * @param topic
     * @return
     * @throws GeneralServiceException
     */
    public static List<Map<Object, byte[]>> getRecordValueByOffset(List<Long> offsets, String bootstrapServers, String topic) throws GeneralServiceException {
        List<Map<Object, byte[]>> results = Lists.newArrayList();
        Map<Long, FutureTask<Map<Object, byte[]>>> taskList = Maps.newHashMap();

        for (Long offset : offsets) {
            FutureTask<Map<Object, byte[]>> task = new FutureTask<>(() -> {
                Map<Object, byte[]> data = Maps.newHashMap();
                KafkaConsumer<byte[], byte[]> consumer = null;
                try {
                    consumer = createConsumer(bootstrapServers);
                    List<PartitionInfo> partitionInfos = consumer.partitionsFor(topic);
                    for (PartitionInfo partitionInfo : partitionInfos) {
                        TopicPartition topicPartition = new TopicPartition(partitionInfo.topic(), partitionInfo.partition());
                        consumer.assign(Collections.singleton(topicPartition));
                        consumer.seek(topicPartition, offset);
                        logger.info("assignment topicPartition is -> " + consumer.assignment().toString());
                        ConsumerRecords<byte[], byte[]> records = consumer.poll(POLL_TIMEOUT_MS);
                        while (!records.isEmpty()) {
                            Iterator<ConsumerRecord<byte[], byte[]>> it = records.iterator();
                            if (it.hasNext()) {
                                ConsumerRecord<byte[], byte[]> record = it.next();
                                data.put(record.timestamp(), record.value());
                                break;
                            }
                        }
                        consumer.commitSync();
                    }
                } catch (Exception e) {
                    logger.error("", e);
                } finally {
                    if (consumer != null) {
                        consumer.close();
                    }
                }
                return data;
            });
            taskList.put(offset, task);
            new Thread(task).start();
        }


        for (Long id : taskList.keySet()) {
            try {
                Map<Object, byte[]> values = taskList.get(id).get();
                results.add(values);
            } catch (Exception e) {
                throw new GeneralServiceException("no message find in topic", e);
            }
        }
        return results;
    }


    public static void main(String[] args) {
        String servers = "192.168.1.110:9093,192.168.1.111:9093,192.168.1.112:9093";
        String topic = "source_hy2_test2";
        List<byte[]> offsets = OffsetOperator.getTop5Records(5, topic, servers);
        for (byte[] a : offsets) {
            System.out.println(new String(a));
        }
        System.out.println("=====================================================");
    }


}
