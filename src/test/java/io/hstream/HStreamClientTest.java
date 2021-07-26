package io.hstream;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HStreamClientTest {

  private static final Logger logger = LoggerFactory.getLogger(HStreamClientTest.class);

  private HStreamClient client;
  private static final String serviceUrl = "localhost:6570";
  private static final String TEST_STREAM = "test_stream";
  private static final String TEST_SUBSCRIPTION = "test_subscription";

  @BeforeEach
  public void setUp() {
    client = HStreamClient.builder().serviceUrl(serviceUrl).build();
    client.createStream(TEST_STREAM);
  }

  @AfterEach
  public void cleanUp() {
    TestUtils.deleteAllSubscriptions(client);
    client.deleteStream(TEST_STREAM);
  }

  @Test
  public void testWriteRawRecord() throws Exception {
    Consumer consumer =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Producer producer = client.newProducer().stream(TEST_STREAM).build();
    Random random = new Random();
    byte[] rawRecord = new byte[100];
    random.nextBytes(rawRecord);
    RecordId recordId = producer.write(rawRecord);

    List<ReceivedRawRecord> receivedRawRecords = consumer.pollRawRecords();
    Assertions.assertEquals(recordId, receivedRawRecords.get(0).getRecordId());

    consumer.close();
  }

  @Test
  public void testWriteHRecord() throws Exception {
    Consumer consumer =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Producer producer = client.newProducer().stream(TEST_STREAM).build();
    HRecord hRecord =
        HRecord.newBuilder().put("key1", 10).put("key2", "hello").put("key3", true).build();
    RecordId recordId = producer.write(hRecord);

    List<ReceivedHRecord> receivedHRecords = consumer.pollHRecords();
    Assertions.assertEquals(recordId, receivedHRecords.get(0).getRecordId());

    consumer.close();
  }

  @Test
  public void testDuplicateSubscribe() throws Exception {
    Consumer consumer1 =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Assertions.assertThrows(
        HStreamDBClientException.SubscribeException.class,
        () -> {
          Consumer consumer2 =
              client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
                  .maxPollRecords(100)
                  .pollTimeoutMs(100)
                  .build();
          consumer2.close();
        });

    consumer1.close();
  }

  @Test
  public void testConsumerSession() throws Exception {
    Consumer consumer1 =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    consumer1.close();

    Thread.sleep(5000);

    Consumer consumer2 =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    consumer2.close();
  }

  @Test
  public void testStreamQuery() {
    Publisher<HRecord> publisher =
        client.streamQuery(
            "select * from " + TEST_STREAM + " where temperature > 30 emit changes;");
    AtomicInteger receivedCount = new AtomicInteger(0);
    Observer<HRecord> observer =
        new Observer<HRecord>() {
          @Override
          public void onNext(HRecord value) {
            logger.info("get hrecord: {}", value);
            receivedCount.incrementAndGet();
          }

          @Override
          public void onError(Throwable t) {
            throw new RuntimeException(t);
          }

          @Override
          public void onCompleted() {}
        };
    publisher.subscribe(observer);

    try {
      Thread.sleep(3000);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }

    logger.info("begin to write");

    Producer producer = client.newProducer().stream(TEST_STREAM).build();
    HRecord hRecord1 = HRecord.newBuilder().put("temperature", 29).put("humidity", 20).build();
    HRecord hRecord2 = HRecord.newBuilder().put("temperature", 34).put("humidity", 21).build();
    HRecord hRecord3 = HRecord.newBuilder().put("temperature", 35).put("humidity", 22).build();
    producer.write(hRecord1);
    producer.write(hRecord2);
    producer.write(hRecord3);

    try {
      Thread.sleep(10000);
      Assertions.assertEquals(2, receivedCount.get());
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void testWriteBatchRawRecord() throws Exception {
    Consumer consumer =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Producer producer =
        client.newProducer().stream(TEST_STREAM).enableBatch().recordCountLimit(10).build();
    Random random = new Random();
    final int count = 100;
    CompletableFuture<RecordId>[] recordIdFutures = new CompletableFuture[count];
    for (int i = 0; i < count; ++i) {
      byte[] rawRecord = new byte[100];
      random.nextBytes(rawRecord);
      CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
      recordIdFutures[i] = future;
    }
    CompletableFuture.allOf(recordIdFutures).join();

    logger.info("producer finish");

    int readCount = 0;
    while (readCount < count) {
      List<ReceivedRawRecord> receivedRawRecords = consumer.pollRawRecords();
      for (ReceivedRawRecord receivedRawRecord : receivedRawRecords) {
        System.out.println(receivedRawRecord.getRecordId());
        Assertions.assertEquals(recordIdFutures[readCount].join(), receivedRawRecord.getRecordId());
        ++readCount;
      }
    }

    consumer.close();
  }

  @Test
  public void testWriteBatchRawRecordMultiThread() throws Exception {
    Consumer consumer =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Producer producer =
        client.newProducer().stream(TEST_STREAM).enableBatch().recordCountLimit(10).build();
    Random random = new Random();
    final int count = 100;
    CompletableFuture<RecordId>[] recordIdFutures = new CompletableFuture[count];

    Thread thread1 =
        new Thread(
            () -> {
              for (int i = 0; i < count / 2; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
                recordIdFutures[i] = future;
              }
            });

    Thread thread2 =
        new Thread(
            () -> {
              for (int i = count / 2; i < count; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
                recordIdFutures[i] = future;
              }
            });

    thread1.start();
    thread2.start();

    int readCount = 0;
    while (readCount < count) {
      List<ReceivedRawRecord> receivedRawRecords = consumer.pollRawRecords();
      for (ReceivedRawRecord receivedRawRecord : receivedRawRecords) {
        System.out.println(receivedRawRecord.getRecordId());
        ++readCount;
      }
    }

    Assertions.assertEquals(count, readCount);

    consumer.close();
  }

  @Test
  public void testFlush() throws Exception {
    Consumer consumer =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Producer producer =
        client.newProducer().stream(TEST_STREAM).enableBatch().recordCountLimit(100).build();
    Random random = new Random();
    final int count = 10;
    CompletableFuture<RecordId>[] recordIdFutures = new CompletableFuture[count];
    for (int i = 0; i < count; ++i) {
      byte[] rawRecord = new byte[100];
      random.nextBytes(rawRecord);
      CompletableFuture<RecordId> future = producer.writeAsync(rawRecord);
      recordIdFutures[i] = future;
    }
    producer.flush();

    // CompletableFuture.allOf(recordIdFutures).join();

    logger.info("producer finish");

    int readCount = 0;
    while (readCount < count) {
      List<ReceivedRawRecord> receivedRawRecords = consumer.pollRawRecords();
      for (ReceivedRawRecord receivedRawRecord : receivedRawRecords) {
        System.out.println(receivedRawRecord.getRecordId());
        Assertions.assertEquals(recordIdFutures[readCount].join(), receivedRawRecord.getRecordId());
        ++readCount;
      }
    }

    consumer.close();
  }

  @Test
  public void testFlushMultiThread() throws Exception {
    Consumer consumer =
        client.newConsumer().subscription(TEST_SUBSCRIPTION).stream(TEST_STREAM)
            .maxPollRecords(100)
            .pollTimeoutMs(100)
            .build();

    Producer producer =
        client.newProducer().stream(TEST_STREAM).enableBatch().recordCountLimit(100).build();
    Random random = new Random();
    final int count = 10;

    Thread thread1 =
        new Thread(
            () -> {
              for (int i = 0; i < count; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                producer.writeAsync(rawRecord);
              }
              producer.flush();
            });

    Thread thread2 =
        new Thread(
            () -> {
              for (int i = 0; i < count; ++i) {
                byte[] rawRecord = new byte[100];
                random.nextBytes(rawRecord);
                producer.writeAsync(rawRecord);
              }
              producer.flush();
            });

    thread1.start();
    thread2.start();

    int readCount = 0;
    while (readCount < count * 2) {
      List<ReceivedRawRecord> receivedRawRecords = consumer.pollRawRecords();
      for (ReceivedRawRecord receivedRawRecord : receivedRawRecords) {
        System.out.println(receivedRawRecord.getRecordId());
        ++readCount;
      }
    }

    consumer.close();
  }
}
